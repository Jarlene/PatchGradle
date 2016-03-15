package com.baidu.music.classpatch
/**
 * Created by Administrator on 2016/3/14.
 */


import org.apache.commons.codec.digest.DigestUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import com.baidu.music.classpatch.utils.AndroidUtils
import com.baidu.music.classpatch.utils.FileUtils
import com.baidu.music.classpatch.utils.MapUtils
import com.baidu.music.classpatch.utils.Processor
import com.baidu.music.classpatch.utils.SetUtils

class ClassPatchGradlePlugin implements Plugin<Project>{

    private static final String MAPPING_TXT = "mapping.txt"
    private static final String HASH_TXT = "hash.txt"
    private static final String CLASS_PATCH = "ClassPatch";
    private static final String PATCH_DIR = "PatchDir";
    private static final String NPATCHES = "nPatches"

    HashSet<String> includePackage;
    HashSet<String> excludeClass;

    def patchList = []
    def beforeDexTasks = []

    @Override
    void apply(Project project) {

        Map hashMap // 对应的
        File parentDir
        File patchDir

        project.extensions.create(CLASS_PATCH, ApkExtension, project)

        project.afterEvaluate {
            def extension = project.extensions.findByName(CLASS_PATCH) as ApkExtension
            includePackage = extension.includePackage
            excludeClass = extension.excludeClass

            project.android.applicationVariants.each{ variant ->
                def preDexTask = project.tasks.findByName("preDex${variant.name.capitalize()}")
                def dexTask = project.tasks.findByName("dex${variant.name.capitalize()}")
                def proguardTask = project.tasks.findByName("proguard${variant.name.capitalize()}")
                def processManifestTask = project.tasks.findByName("process${variant.name.capitalize()}Manifest")
                def manifestFile = processManifestTask.outputs.files.files[0]
                def oldPatchDir = FileUtils.getFileFromProperty(project, PATCH_DIR)
                if (oldPatchDir) {
                    def mappingFile = FileUtils.getVariantFile(oldPatchDir, variant, MAPPING_TXT)
                    AndroidUtils.applymapping(proguardTask, mappingFile)
                    def hashFile = FileUtils.getVariantFile(oldPatchDir, variant, HASH_TXT)
                    hashMap = MapUtils.parseMap(hashFile)
                }
                def dirName = variant.dirName
                parentDir = new File("${project.buildDir}/outputs/patch")
                def outputDir = new File("${patchDir}/${dirName}")
                def hashFile = new File(outputDir, "hash.txt")

                Closure prepareClosure = {
                    def applicationName = AndroidUtils.getApplication(manifestFile)
                    if (applicationName != null) {
                        excludeClass.add(applicationName)
                    }
                    outputDir.mkdirs()
                    if (!hashFile.exists()) {
                        hashFile.createNewFile()
                    }

                    if (oldPatchDir) {
                        patchDir = new File("${parentDir}/${dirName}/patches")
                        patchDir.mkdirs()
                        patchList.add(patchDir)
                    }
                }

                def patchString = "ClassPathc${variant.name.capitalize()}Patch"
                project.task(patchString) << {
                    if (patchDir) {
                        AndroidUtils.dex(project, patchDir)
                    }
                }
                def patchTask = project.tasks[patchString]

                Closure copyMappingClosure = {
                    if (proguardTask) {
                        def mapFile = new File("${project.buildDir}/outputs/mapping/${variant.dirName}/mapping.txt")
                        def newMapFile = new File("${parentDir}/${variant.dirName}/mapping.txt");
                        org.apache.commons.io.FileUtils.copyFile(mapFile, newMapFile)
                    }
                }

                if (preDexTask) {
                    def jarBeforePreDex = "jarBeforePreDex${variant.name.capitalize()}"
                    project.task(jarBeforePreDex) << {
                        Set<File> inputFiles = preDexTask.inputs.files.files
                        inputFiles.each { inputFile ->
                            def path = inputFile.absolutePath
                            if (Processor.shouldProcessPreDexJar(path)) {
                                Processor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                            }
                        }
                    }

                    def jarBeforePreDexTask = project.tasks[jarBeforePreDex]
                    jarBeforePreDexTask.dependsOn preDexTask.taskDependencies.getDependencies(preDexTask)
                    preDexTask.dependsOn jarBeforePreDexTask

                    jarBeforePreDexTask.doFirst(prepareClosure)

                    def classBeforeDex = "classBeforeDex${variant.name.capitalize()}"

                    project.task(classBeforeDex) << {
                        Set<File> inputFiles = dexTask.inputs.files.files
                        inputFiles.each { inputFile ->
                            def path = inputFile.absolutePath
                            if (SetUtils.isIncluded(path, includePackage)) {
                                if (!SetUtils.isExcluded(path, excludeClass)) {
                                    def bytes = Processor.processClass(inputFile)
                                    path = path.split("${dirName}/")[1]
                                    def hash = DigestUtils.shaHex(bytes)
                                    hashFile.append(MapUtils.format(path, hash))

                                    if (MapUtils.notSame(hashMap, path, hash)) {
                                        FileUtils.copyBytesToFile(inputFile.bytes, FileUtils.touchFile(patchDir, path))
                                    }
                                }
                            }
                        }
                    }
                    def classBeforeDexTask = project.tasks[classBeforeDex]
                    classBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                    dexTask.dependsOn classBeforeDexTask

                    classBeforeDexTask.doLast(copyMappingClosure)

                    patchTask.dependsOn classBeforeDexTask
                    beforeDexTasks.add(classBeforeDexTask)

                } else {
                    def jarBeforeDex = "jarBeforeDex${variant.name.capitalize()}"
                    project.task(jarBeforeDex) << {
                        Set<File> inputFiles = dexTask.inputs.files.files
                        inputFiles.each { inputFile ->
                            def path = inputFile.absolutePath
                            if (path.endsWith(".jar")) {
                                Processor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                            }
                        }
                    }
                    def jarBeforeDexTask = project.tasks[jarBeforeDex]
                    jarBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                    dexTask.dependsOn jarBeforeDexTask

                    jarBeforeDexTask.doFirst(prepareClosure)
                    jarBeforeDexTask.doLast(copyMappingClosure)

                    patchTask.dependsOn jarBeforeDexTask
                    beforeDexTasks.add(jarBeforeDexTask)
                }
            }

            project.task(NPATCHES) << {
                patchList.each { patchFile ->
                    AndroidUtils.dex(project, patchFile)
                }
            }
            beforeDexTasks.each {
                project.tasks[NPATCHES].dependsOn it
            }
        }
    }
}
