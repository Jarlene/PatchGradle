package com.baidu.music.classpatch
/**
 * Created by Administrator on 2016/3/14.
 */

import org.gradle.api.Project
class ApkExtension {

    HashSet<String> includePackage = []
    HashSet<String> excludeClass = []
    ApkExtension(Project project) {
    }
}
