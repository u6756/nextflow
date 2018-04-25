/*
 * Copyright (c) 2013-2018, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2018, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.conda

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowVariable
import groovyx.gpars.dataflow.LazyDataflowVariable
import nextflow.Global
import nextflow.file.FileMutex
import nextflow.util.CacheHelper
import nextflow.util.Duration
import nextflow.util.Escape
import org.yaml.snakeyaml.Yaml
/**
 * Handle Conda environment creation and caching
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class CondaCache {

    /**
     * Cache the prefix path for each Conda environment
     */
    static final private Map<String,DataflowVariable<Path>> condaPrefixPaths = new ConcurrentHashMap<>()

    /**
     * The Conda settings defined in the nextflow config file
     */
    private CondaConfig config

    /**
     * The current system environment
     */
    private Map<String,String> env

    /**
     * Timeout after which the environment creation is aborted
     */
    private Duration createTimeout = Duration.of('10min')

    /** Only for debugging purpose - do not use */
    @PackageScope
    CondaCache() {}

    /**
     * Create a Conda env cache object
     *
     * @param config A {@link Map} object
     * @param env The environment configuration object. Specifying {@code null} the current system environment is used
     */
    CondaCache(CondaConfig config, Map<String,String> env=null) {
        this.config = config
        this.env = env ?: System.getenv()
    }


    /**
     * Retrieve the directory where store the conda environment.
     *
     * If tries these setting in the following order:
     * 1) {@code conda.cacheDir} setting in the nextflow config file;
     * 2) the {@code $workDir/conda} path
     *
     * @return
     *      the {@code Path} where store the conda envs
     */
    @PackageScope
    Path getCacheDir() {

        if( config.createTimeout )
            createTimeout = config.createTimeout as Duration

        def cacheDir = config.cacheDir as Path
        if( !cacheDir )
            cacheDir = Global.session.workDir.resolve('conda')

        if( cacheDir.fileSystem != FileSystems.default ) {
            throw new IOException("Cannot store Conda environments to a remote work directory -- Use a POSIX compatible work directory or specify an alternative path with the `conda.cacheDir` config setting")
        }

        if( !cacheDir.exists() && !cacheDir.mkdirs() ) {
            throw new IOException("Failed to create Conda cache directory: $cacheDir -- Make sure a file with the same does not exist and you have write permission")
        }

        return cacheDir
    }

    @PackageScope
    boolean isEnvFile(String str) {
        str.endsWith('.yml') || str.endsWith('.yaml')
    }

    /**
     * Get the path on the file system where store a Conda environment
     *
     * @param condaEnv The conda environment
     * @return the conda unique prefix {@link Path} where the env is created
     */
    @PackageScope
    Path condaPrefixPath(String condaEnv) {
        assert condaEnv

        final DEFAULT_NAME = 'env'
        // check if it's a YAML file
        String prefix
        if( isEnvFile(condaEnv) ) {
            final path = condaEnv as Path
            final text = path.text
            final yaml = (Map)new Yaml().load(text)
            final name = yaml.name ?: DEFAULT_NAME
            prefix = "$name-${CacheHelper.hasher(text).hash().toString()}"
        }
        else {
            prefix = "$DEFAULT_NAME-${CacheHelper.hasher(condaEnv).hash().toString()}"
        }

        getCacheDir().resolve(prefix)
    }

    /**
     * Run the conda tool to create an environment in the file system.
     *
     * @param condaEnv The conda environment definition
     * @return the conda environment prefix {@link Path}
     */
    @PackageScope
    Path createLocalCondaEnv(String condaEnv) {
        final prefixPath = condaPrefixPath(condaEnv)
        final file = new File("${prefixPath.parent}/.${prefixPath.name}.lock")
        final wait = "Another Nextflow instance is creatign the Conda environment $condaEnv -- please wait it completes"
        final err =  "Unable to acquire exclusive lock after $createTimeout on file: $file"

        final mutex = new FileMutex(target: file, timeout: createTimeout, waitMessage: wait, errorMessage: err)
        try {
            mutex .lock { createLocalCondaEnv0(condaEnv, prefixPath) }
        }
        finally {
            file.delete()
        }

        return prefixPath
    }

    @PackageScope
    Path makeAbsolute( String envFile ) {
        Paths.get(envFile).toAbsolutePath()
    }

    @PackageScope
    Path createLocalCondaEnv0(String condaEnv, Path prefixPath) {

        if( prefixPath.isDirectory() ) {
            log.debug "Conda found local env for environment=$condaEnv; path=$prefixPath"
            return prefixPath
        }
        log.trace "Conda create env=$condaEnv"

        log.info "Creating Conda env: $condaEnv [cache $prefixPath]"

        final isFile = isEnvFile(condaEnv)
        final String cmd = ( isFile
                    ? "conda env create --prefix ${Escape.path(prefixPath)} --file ${Escape.path(makeAbsolute(condaEnv))}"
                    : "conda create --mkdir --yes --quiet --prefix ${Escape.path(prefixPath)} $condaEnv")
        try {
            runCommand( cmd )
            log.debug "Conda create complete env=$condaEnv path=$prefixPath"
        }
        catch( Exception e ){
            // clean-up to avoid to keep eventually corrupted image file
            prefixPath.delete()
            throw e
        }
        return prefixPath
    }

    @PackageScope
    int runCommand( String cmd ) {
        log.trace """Conda create
                     command: $cmd
                     timeout: $createTimeout""".stripIndent()

        final max = createTimeout.toMillis()
        final builder = new ProcessBuilder(['bash','-c',cmd])
        final proc = builder.start()
        final err = new StringBuilder()
        proc.consumeProcessErrorStream(err)
        proc.waitForOrKill(max)
        def status = proc.exitValue()
        if( status != 0 ) {
            def msg = "Failed to create Conda environment\n  command: $cmd\n  status : $status\n  message:\n"
            msg += err.toString().trim().indent('    ')
            throw new IllegalStateException(msg)
        }
        return status
    }

    /**
     * Given a remote image URL returns a {@link DataflowVariable} which holds
     * the local image path.
     *
     * This method synchronise multiple concurrent requests so that only one
     * image download is actually executed.
     *
     * @param condaEnv
     *      Conda environment string
     * @return
     *      The {@link DataflowVariable} which hold (and pull) the local image file
     */
    @PackageScope
    DataflowVariable<Path> getLazyImagePath(String condaEnv) {
        if( condaEnv in condaPrefixPaths ) {
            log.trace "Conda found local environment `$condaEnv`"
            return condaPrefixPaths[condaEnv]
        }

        synchronized (condaPrefixPaths) {
            def result = condaPrefixPaths[condaEnv]
            if( result == null ) {
                result = new LazyDataflowVariable<Path>({ createLocalCondaEnv(condaEnv) })
                condaPrefixPaths[condaEnv] = result
            }
            else {
                log.trace "Conda found local cache for environment `$condaEnv` (2)"
            }
            return result
        }
    }

    /**
     * Create a conda environment caching it in the file system.
     *
     * This method synchronise multiple concurrent requests so that only one
     * environment is actually created.
     *
     * @param condaEnv The conda environment string
     * @return the local environment path prefix {@link Path}
     */
    Path getCachePathFor(String condaEnv) {
        def promise = getLazyImagePath(condaEnv)
        def result = promise.getVal()
        if( promise.isError() )
            throw new IllegalStateException(promise.getError())
        if( !result )
            throw new IllegalStateException("Cannot create Conda environment `$condaEnv`")
        log.trace "Conda cache for env `$condaEnv` path=$result"
        return result
    }

}
