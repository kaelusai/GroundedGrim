/**
 *          GroundedGrim Build Configuration
 *          Fork of GrimAC by KaelusMC
 *          https://github.com/KaelusMC/GroundedGrim
 *
 * Build Flags:
 * -PshadePE=true   - Enables 'lite' mode
 * -Prelocate=false - Adds 'no_relocate' modifier
 * -Prelease=true   - Removes commit/modifiers for release build
 *
 * Logic in: buildSrc/versioning/BuildConfig.kt & VersionUtil.kt
 */

import versioning.BuildConfig
import versioning.VersionUtil

BuildConfig.init(project)

val baseVersion = "2.3.75"
group = "ac.grim.grimac"
version = VersionUtil.computeVersion(project, baseVersion)
description = "GroundedGrim - fork of GrimAC by KaelusMC. Simulation anticheat for 1.8-26.3, powered by PacketEvents 2.0."

ext["timestamp"] = System.currentTimeMillis().toString()
ext["git_branch"] = VersionUtil.getGitBranch(project, true)
ext["git_commit"] = VersionUtil.getGitCommitHash(project, true)
ext["git_org"] = System.getenv("GRIM_GIT_ORG") ?: VersionUtil.getGitUser(project)
ext["git_repo"] = System.getenv("GRIM_GIT_REPO") ?: "GroundedGrim"

println("Build configuration:")
println("    shadePE            = ${BuildConfig.shadePE}")
println("    relocate           = ${BuildConfig.relocate}")
println("    mavenLocalOverride = ${BuildConfig.mavenLocalOverride}")
println("    release            = ${BuildConfig.release}")
println("    version            = $version")

tasks.register("printVersion") {
    group = "versioning"
    description = "Prints the computed project version"
    inputs.property("version", version.toString())
    doLast {
        println("VERSION=${inputs.properties["version"]}")
    }
}

// ---------- Java Compile Optimization ----------
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.isFork = true
        options.isIncremental = true
    }
}
