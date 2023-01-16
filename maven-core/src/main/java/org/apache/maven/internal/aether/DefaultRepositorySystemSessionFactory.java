package org.apache.maven.internal.aether;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.bridge.MavenRepositorySystem;
import org.apache.maven.eventspy.internal.EventSpyDispatcher;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.NoLocalRepositoryManagerException;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.resolution.ResolutionErrorPolicy;
import org.eclipse.aether.spi.localrepo.LocalRepositoryManagerFactory;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultAuthenticationSelector;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import org.eclipse.aether.util.repository.SimpleResolutionErrorPolicy;
import org.eclipse.sisu.Nullable;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * @since 3.3.0
 */
@Named
public class DefaultRepositorySystemSessionFactory
{
    @Inject
    private Logger logger;

    @Inject
    private ArtifactHandlerManager artifactHandlerManager;

    @Inject
    private RepositorySystem repoSystem;

    @Inject
    @Nullable
    @Named( "simple" )
    private LocalRepositoryManagerFactory simpleLocalRepoMgrFactory;

    @Inject
    @Nullable
    @Named( "ide" )
    private WorkspaceReader workspaceRepository;

    @Inject
    private SettingsDecrypter settingsDecrypter;

    @Inject
    private EventSpyDispatcher eventSpyDispatcher;

    @Inject
    MavenRepositorySystem mavenRepositorySystem;

    public DefaultRepositorySystemSession newRepositorySession( MavenExecutionRequest request )
    {
        // 1. 创建session对象
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

        session.setCache( request.getRepositoryCache() );

        Map<Object, Object> configProps = new LinkedHashMap<>();
        configProps.put( ConfigurationProperties.USER_AGENT, getUserAgent() );
        configProps.put( ConfigurationProperties.INTERACTIVE, request.isInteractiveMode() );
        configProps.putAll( request.getSystemProperties() );
        configProps.putAll( request.getUserProperties() );

        // 2. copy isOffline
        session.setOffline( request.isOffline() );
        // 3. copy checkSumPolicy
        session.setChecksumPolicy( request.getGlobalChecksumPolicy() );

        // 4. copy updatePolicy
        if ( request.isNoSnapshotUpdates() )
        {
            session.setUpdatePolicy( RepositoryPolicy.UPDATE_POLICY_NEVER );
        }
        else if ( request.isUpdateSnapshots() )
        {
            session.setUpdatePolicy( RepositoryPolicy.UPDATE_POLICY_ALWAYS );
        }
        else
        {
            session.setUpdatePolicy( null );
        }

        // 5. 赋值errorPolicy , 可ignore
        // 默认cacheNotFound = ture , cacheTransferError = false
        int errorPolicy = 0;
        errorPolicy |= request.isCacheNotFound() ? ResolutionErrorPolicy.CACHE_NOT_FOUND
            : ResolutionErrorPolicy.CACHE_DISABLED;
        errorPolicy |= request.isCacheTransferError() ? ResolutionErrorPolicy.CACHE_TRANSFER_ERROR
            : ResolutionErrorPolicy.CACHE_DISABLED;
        session.setResolutionErrorPolicy(
            new SimpleResolutionErrorPolicy( errorPolicy, errorPolicy | ResolutionErrorPolicy.CACHE_NOT_FOUND ) );

        session.setArtifactTypeRegistry( RepositoryUtils.newArtifactTypeRegistry( artifactHandlerManager ) );

        // 6. 创建本地仓库对象
        LocalRepository localRepo = new LocalRepository( request.getLocalRepository().getBasedir() );

        // 7. 不用看里面的代码,就是创建了一个EnhancedLocalRepositoryManager：用于在LocalRepository寻找Artifact
        session.setLocalRepositoryManager( repoSystem.newLocalRepositoryManager( session, localRepo ) );

        // 忽略: request.getWorkspaceReader() == null
        if ( request.getWorkspaceReader() != null )
        {
            session.setWorkspaceReader( request.getWorkspaceReader() );
        }
        else
        {
            // ignore , none ide workspaceRepository
            session.setWorkspaceReader( workspaceRepository );
        }

        // 8. ignore 加密相关 ----------------------------------------------------
        DefaultSettingsDecryptionRequest decrypt = new DefaultSettingsDecryptionRequest();
        decrypt.setProxies( request.getProxies() );
        decrypt.setServers( request.getServers() );
        SettingsDecryptionResult decrypted = settingsDecrypter.decrypt( decrypt );

        if ( logger.isDebugEnabled() )
        {
            for ( SettingsProblem problem : decrypted.getProblems() )
            {
                logger.debug( problem.getMessage(), problem.getException() );
            }
        }
        // 8. -------------------------------------------------

        // 9. 默认Mirror选择器,根据 <mirrorOf>mirrorOf</mirrorOf> 匹配 RemoteRepositoryId
        DefaultMirrorSelector mirrorSelector = new DefaultMirrorSelector();
        for ( Mirror mirror : request.getMirrors() )
        {
            mirrorSelector.add(
                                mirror.getId(),
                                mirror.getUrl(),
                                mirror.getLayout(),
                                false,
                                mirror.getMirrorOf(),
                                mirror.getMirrorOfLayouts()
            );
        }
        session.setMirrorSelector( mirrorSelector );

        // 9. -------------------------------------------------

        // 10. ignore ,  proxy相关 -------------------------------------------------
        DefaultProxySelector proxySelector = new DefaultProxySelector();
        for ( Proxy proxy : decrypted.getProxies() )
        {
            AuthenticationBuilder authBuilder = new AuthenticationBuilder();
            authBuilder.addUsername( proxy.getUsername() ).addPassword( proxy.getPassword() );
            proxySelector.add(
                new org.eclipse.aether.repository.Proxy( proxy.getProtocol(), proxy.getHost(), proxy.getPort(),
                                                         authBuilder.build() ), proxy.getNonProxyHosts() );
        }
        session.setProxySelector( proxySelector );

        // 10. -------------------------------------------------

        // 11. ignore 私服上传(认证)相关 -------------------------------------------------
        DefaultAuthenticationSelector authSelector = new DefaultAuthenticationSelector();
        for ( Server server : decrypted.getServers() )
        {
            AuthenticationBuilder authBuilder = new AuthenticationBuilder();
            authBuilder.addUsername( server.getUsername() ).addPassword( server.getPassword() );
            authBuilder.addPrivateKey( server.getPrivateKey(), server.getPassphrase() );
            authSelector.add( server.getId(), authBuilder.build() );

            if ( server.getConfiguration() != null )
            {
                Xpp3Dom dom = (Xpp3Dom) server.getConfiguration();
                for ( int i = dom.getChildCount() - 1; i >= 0; i-- )
                {
                    Xpp3Dom child = dom.getChild( i );
                    if ( "wagonProvider".equals( child.getName() ) )
                    {
                        dom.removeChild( i );
                    }
                }

                XmlPlexusConfiguration config = new XmlPlexusConfiguration( dom );
                configProps.put( "aether.connector.wagon.config." + server.getId(), config );
            }

            configProps.put( "aether.connector.perms.fileMode." + server.getId(), server.getFilePermissions() );
            configProps.put( "aether.connector.perms.dirMode." + server.getId(), server.getDirectoryPermissions() );
        }
        session.setAuthenticationSelector( authSelector );
        // 11. -------------------------------------------------

        // just for logging
        session.setTransferListener( request.getTransferListener() );

        // just for logging
        session.setRepositoryListener( eventSpyDispatcher.chainListener( new LoggingRepositoryListener( logger ) ) );

        session.setUserProperties( request.getUserProperties() );
        session.setSystemProperties( request.getSystemProperties() );
        session.setConfigProperties( configProps );

        // 根据mirrorSelector
        // 匹配到的repository的url全都替换为
        // <mirror> <url> {url} </url> </mirror> : mirror的url (还有id)
        mavenRepositorySystem.injectMirror( request.getRemoteRepositories(), request.getMirrors() );
        mavenRepositorySystem.injectProxy( session, request.getRemoteRepositories() );
        mavenRepositorySystem.injectAuthentication( session, request.getRemoteRepositories() );

        mavenRepositorySystem.injectMirror( request.getPluginArtifactRepositories(), request.getMirrors() );
        mavenRepositorySystem.injectProxy( session, request.getPluginArtifactRepositories() );
        mavenRepositorySystem.injectAuthentication( session, request.getPluginArtifactRepositories() );

        return session;
    }

    private String getUserAgent()
    {
        return "Apache-Maven/" + getMavenVersion() + " (Java " + System.getProperty( "java.version" ) + "; "
            + System.getProperty( "os.name" ) + " " + System.getProperty( "os.version" ) + ")";
    }

    private String getMavenVersion()
    {
        Properties props = new Properties();

        try ( InputStream is = getClass().getResourceAsStream(
            "/META-INF/maven/org.apache.maven/maven-core/pom.properties" ) )
        {
            if ( is != null )
            {
                props.load( is );
            }
        }
        catch ( IOException e )
        {
            logger.debug( "Failed to read Maven version", e );
        }

        return props.getProperty( "version", "unknown-version" );
    }

}
