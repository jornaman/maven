package org.apache.maven.project;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *                              
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.MavenConstants;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.MavenMetadataSource;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryFactory;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.inheritance.ModelInheritanceAssembler;
import org.apache.maven.project.injection.ModelDefaultsInjector;
import org.apache.maven.project.interpolation.ModelInterpolationException;
import org.apache.maven.project.interpolation.ModelInterpolator;
import org.apache.maven.project.path.PathTranslator;
import org.apache.maven.project.validation.ModelValidationResult;
import org.apache.maven.project.validation.ModelValidator;
import org.apache.maven.settings.MavenSettings;
import org.apache.maven.settings.MavenSettingsBuilder;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @version $Id: DefaultMavenProjectBuilder.java,v 1.37 2005/03/08 01:55:22
 *          trygvis Exp $
 */
public class DefaultMavenProjectBuilder
    extends AbstractLogEnabled
    implements MavenProjectBuilder, Initializable
{
    private ArtifactResolver artifactResolver;

    private ArtifactFactory artifactFactory;

    private ModelInheritanceAssembler modelInheritanceAssembler;

    private ModelValidator validator;

    private MavenXpp3Reader modelReader;

    private PathTranslator pathTranslator;

    private ModelDefaultsInjector modelDefaultsInjector;

    private ModelInterpolator modelInterpolator;

    private MavenSettingsBuilder mavenSettingsBuilder;

    private ArtifactRepositoryFactory artifactRepositoryFactory;

    private final Map modelCache = new HashMap();

    public void initialize()
    {
        modelReader = new MavenXpp3Reader();
    }

    // ----------------------------------------------------------------------
    // MavenProjectBuilder Implementation
    // ----------------------------------------------------------------------

    public MavenProject buildWithDependencies( File project, ArtifactRepository localRepository )
        throws ProjectBuildingException
    {
        return build( project, localRepository, true, true );
    }

    public MavenProject build( File project, ArtifactRepository localRepository )
        throws ProjectBuildingException
    {
        return build( project, localRepository, false, true );
    }

    public MavenProject buildFromRepository( Artifact artifact, ArtifactRepository localRepository )
        throws ProjectBuildingException
    {
        return build( artifact.getFile(), localRepository, false, false );
    }

    private MavenProject build( File projectDescriptor, ArtifactRepository localRepository,
                                boolean resolveDependencies, boolean sourceProject )
        throws ProjectBuildingException
    {
        try
        {
            Model superModel = getSuperModel();

            LinkedList lineage = new LinkedList();

            List aggregatedRemoteWagonRepositories = buildArtifactRepositories( superModel.getRepositories() );
            MavenProject project = assembleLineage( projectDescriptor, localRepository, lineage,
                                                    aggregatedRemoteWagonRepositories );

            Model previous = superModel;

            for ( Iterator i = lineage.iterator(); i.hasNext(); )
            {
                Model current = ( (MavenProject) i.next() ).getModel();

                modelInheritanceAssembler.assembleModelInheritance( current, previous );

                previous = current;
            }

            project = processProjectLogic( project, localRepository, resolveDependencies, sourceProject );

            return project;
        }
        catch ( Exception e )
        {
            throw new ProjectBuildingException( "Error building project from " + projectDescriptor, e );
        }
    }

    private MavenProject processProjectLogic( MavenProject project, ArtifactRepository localRepository,
                                              boolean resolveDependencies, boolean sourceProject )
        throws ProjectBuildingException, ModelInterpolationException, ArtifactResolutionException
    {
        Model model = project.getModel();
        String key = createCacheKey( model.getGroupId(), model.getArtifactId(), model.getVersion() );
        Model cachedModel = (Model) modelCache.get( key );
        if ( cachedModel == null || sourceProject )
        {
            modelCache.put( key, model );
        }

        model = modelInterpolator.interpolate( model );

        // interpolation is before injection, because interpolation is off-limits in the injected variables
        modelDefaultsInjector.injectDefaults( model );

        MavenProject parentProject = project.getParent();

        File projectDescriptor = project.getFile();
        if ( sourceProject )
        {
            pathTranslator.alignToBaseDirectory( model, projectDescriptor );
        }

        project = new MavenProject( model );
        project.setFile( projectDescriptor );
        project.setParent( parentProject );
        project.setArtifacts( artifactFactory.createArtifacts( project.getDependencies(), localRepository, null ) );

        // ----------------------------------------------------------------------
        // Typically when the project builder is being used from maven proper
        // the transitive dependencies will not be resolved here because this
        // requires a lot of work when we may only be interested in running
        // something simple like 'm2 clean'. So the artifact collector is used
        // in the dependency resolution phase if it is required by any of the
        // goals being executed. But when used as a component in another piece
        // of code people may just want to build maven projects and have the
        // dependencies resolved for whatever reason: this is why we keep
        // this snippet of code here.
        // ----------------------------------------------------------------------

        if ( resolveDependencies )
        {
            List repos = buildArtifactRepositories( project.getRepositories() );

            MavenMetadataSource sourceReader = new MavenMetadataSource( artifactResolver, this );

            ArtifactResolutionResult result = artifactResolver.resolveTransitively( project.getArtifacts(), repos,
                                                                                    localRepository, sourceReader );

            project.addArtifacts( result.getArtifacts().values() );
        }

        ModelValidationResult validationResult = validator.validate( model );

        if ( validationResult.getMessageCount() > 0 )
        {
            throw new ProjectBuildingException( "Exception while building project: " + validationResult.toString() );
        }

        project.addCompileSourceRoot( project.getBuild().getSourceDirectory() );
        project.addScriptSourceRoot( project.getBuild().getScriptSourceDirectory() );
        project.addTestCompileSourceRoot( project.getBuild().getTestSourceDirectory() );

        return project;
    }

    private MavenProject assembleLineage( File projectDescriptor, ArtifactRepository localRepository,
                                          LinkedList lineage, List aggregatedRemoteWagonRepositories )
        throws ProjectBuildingException
    {
        Model model = readModel( projectDescriptor );
        MavenProject project = assembleLineage( model, localRepository, lineage, aggregatedRemoteWagonRepositories );
        project.setFile( projectDescriptor );

        return project;

    }

    private MavenProject assembleLineage( Model model, ArtifactRepository localRepository, LinkedList lineage,
                                          List aggregatedRemoteWagonRepositories )
        throws ProjectBuildingException
    {
        MavenProject project = new MavenProject( model );

        lineage.addFirst( project );

        Parent parentModel = project.getModel().getParent();

        if ( parentModel != null )
        {
            if ( StringUtils.isEmpty( parentModel.getGroupId() ) )
            {
                throw new ProjectBuildingException( "Missing groupId element from parent element" );
            }
            else if ( StringUtils.isEmpty( parentModel.getArtifactId() ) )
            {
                throw new ProjectBuildingException( "Missing artifactId element from parent element" );
            }
            else if ( StringUtils.isEmpty( parentModel.getVersion() ) )
            {
                throw new ProjectBuildingException( "Missing version element from parent element" );
            }

            //!! (**)
            // ----------------------------------------------------------------------
            // Do we have the necessary information to actually find the parent
            // POMs here?? I don't think so ... Say only one remote repository is
            // specified and that is ibiblio then this model that we just read doesn't
            // have any repository information ... I think we might have to inherit
            // as we go in order to do this.
            // ----------------------------------------------------------------------

            aggregatedRemoteWagonRepositories.addAll(
                buildArtifactRepositories( project.getModel().getRepositories() ) );

            MavenProject parent;
            Model cachedModel = getCachedModel( parentModel.getGroupId(), parentModel.getArtifactId(),
                                                parentModel.getVersion() );
            if ( cachedModel == null )
            {
                File parentPom = findParentModel( parentModel, aggregatedRemoteWagonRepositories, localRepository );

                parent = assembleLineage( parentPom, localRepository, lineage, aggregatedRemoteWagonRepositories );
            }
            else
            {
                parent = assembleLineage( cachedModel, localRepository, lineage, aggregatedRemoteWagonRepositories );
            }
            project.setParent( parent );
        }

        return project;
    }

    private List buildArtifactRepositories( List repositories )
        throws ProjectBuildingException
    {
        MavenSettings settings = null;

        try
        {
            settings = mavenSettingsBuilder.buildSettings();
        }
        catch ( Exception e )
        {
            throw new ProjectBuildingException( "Cannot read settings.", e );
        }

        List repos = new ArrayList();
        for ( Iterator i = repositories.iterator(); i.hasNext(); )
        {
            Repository mavenRepo = (Repository) i.next();

            ArtifactRepository artifactRepo = artifactRepositoryFactory.createArtifactRepository( mavenRepo, settings );

            if ( !repos.contains( artifactRepo ) )
            {
                repos.add( artifactRepo );
            }
        }
        return repos;
    }

    private Model readModel( File file )
        throws ProjectBuildingException
    {
        try
        {
            return modelReader.read( new FileReader( file ) );
        }
        catch ( FileNotFoundException e )
        {
            throw new ProjectBuildingException( "Could not find the model file '" + file.getAbsolutePath() + "'." );
        }
        catch ( Exception e )
        {
            throw new ProjectBuildingException(
                "Error while reading model from file '" + file.getAbsolutePath() + "'.", e );
        }
    }

    private Model readModel( URL url )
        throws ProjectBuildingException
    {
        try
        {
            return modelReader.read( new InputStreamReader( url.openStream() ) );
        }
        catch ( IOException e )
        {
            throw new ProjectBuildingException( "Error while reading model.", e );
        }
        catch ( Exception ex )
        {
            throw new ProjectBuildingException( "Error while building model from " + url.toExternalForm(), ex );
        }
    }

    private File findParentModel( Parent parent, List remoteArtifactRepositories, ArtifactRepository localRepository )
        throws ProjectBuildingException
    {
        Artifact artifact = artifactFactory.createArtifact( parent.getGroupId(), parent.getArtifactId(),
                                                            parent.getVersion(), null, "pom", "pom", null );

        try
        {
            artifactResolver.resolve( artifact, remoteArtifactRepositories, localRepository );
        }
        catch ( ArtifactResolutionException e )
        {
            // @todo use parent.toString() if modello could generate it, or specify in a code segment
            throw new ProjectBuildingException( "Missing parent POM: " + parent.getGroupId() + ":" +
                                                parent.getArtifactId() + "-" + parent.getVersion(), e );
        }

        return artifact.getFile();
    }

    public Model getCachedModel( String groupId, String artifactId, String version )
    {
        return (Model) modelCache.get( createCacheKey( groupId, artifactId, version ) );
    }

    private static String createCacheKey( String groupId, String artifactId, String version )
    {
        return groupId + ":" + artifactId + ":" + version;
    }

    public MavenProject buildSuperProject( ArtifactRepository localRepository )
        throws ProjectBuildingException
    {
        MavenProject project = new MavenProject( getSuperModel() );

        try
        {
            project.setFile( new File( ".", "pom.xml" ) );

            project = processProjectLogic( project, localRepository, false, false );

            return project;
        }
        catch ( ModelInterpolationException e )
        {
            throw new ProjectBuildingException( "Error building super-project", e );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new ProjectBuildingException( "Error building super-project", e );
        }
    }

    // ----------------------------------------------------------------------
    //
    // ----------------------------------------------------------------------

    private Model getSuperModel()
        throws ProjectBuildingException
    {
        URL url = DefaultMavenProjectBuilder.class.getResource( "pom-" + MavenConstants.MAVEN_MODEL_VERSION + ".xml" );

        return readModel( url );
    }
}