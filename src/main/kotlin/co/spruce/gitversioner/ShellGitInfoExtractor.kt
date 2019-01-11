package co.spruce.gitversioner

import org.codehaus.groovy.runtime.ProcessGroovyMethods
import org.gradle.api.Project
import java.util.Collections.emptyList

interface GitInfoExtractor {
    val currentSha1: String?
    val currentBranch: String?
    val localChanges: LocalChanges
    val initialCommitDate: Long
    val commitsToHead: List<String>
    val isGitProjectReady: Boolean
    fun commitDate(rev: String): Long
    fun commitsUpTo(rev: String, args: String = ""): List<String>
}

/**
 * Executes shell commands to get information from git
 */
internal class ShellGitInfoExtractor(val project: Project) : GitInfoExtractor {

    override val currentSha1: String? by lazy {
        if (!isGitProjectReady) return@lazy null
        val sha1 = "git rev-parse HEAD".execute().text().trim()
        if (sha1.isEmpty()) null else sha1
    }

    override val currentBranch: String? by lazy {
        if (!isGitProjectReady) return@lazy null
        val branch = "git symbolic-ref --short -q HEAD".execute().text().trim()
        if (branch.isEmpty()) null else branch
    }

    override val localChanges: LocalChanges by lazy {
        if (!isGitProjectReady) return@lazy NO_CHANGES
        val shortStat = "git diff HEAD --shortstat".execute().text().trim()
        if (shortStat.isEmpty()) return@lazy NO_CHANGES

        return@lazy parseShortStats(shortStat)
    }

    override val initialCommitDate: Long by lazy {
        val initialCommit: String = commitsToHead.lastOrNull() ?: return@lazy 0L
        val time = "git log $initialCommit -n 1 --pretty=format:'%at'".execute()
                .text().replace("\'", "").trim()

        return@lazy if (time.isEmpty()) 0L else time.toLong()
    }

    override fun commitDate(rev: String): Long {
        val time = "git log $rev --pretty=format:'%at' -n 1".execute()
                .text().replace("\'", "").trim()
        return if (time.isEmpty()) 0 else time.toLong()
    }

    override val commitsToHead: List<String> by lazy { commitsUpTo("HEAD") }

    override val isGitProjectReady: Boolean by lazy {
        val result = "git status".execute()

        when (result.exitValue()) {
            0 -> true
            69 -> {
                println("git returned with error 69\n" +
                        "If you are a mac user that message is telling you is that you need to open the " +
                        "application XCode on your Mac OS X/macOS and since it hasn’t run since the last " +
                        "update, you need to accept the new license EULA agreement that’s part of the " +
                        "updated XCode.\n\n" +
                        "tl;dr run\n" +
                        "\txcode-select --install")
                false
            }
            else -> {
                println("ERROR: can't generate a git version, this is not a git project")
                println(" -> Not a git repository (or any of the parent directories): .git")
                false
            }
        }
    }

    override fun commitsUpTo(rev: String, args: String): List<String> {
        val cmd = "git rev-list $rev $args"

        var lines: List<String>
        var process = cmd.execute(waitFor = false)
        try {
            lines = process.inputStream.bufferedReader().readLines()
        } catch (e: Exception) {
            try {
                process = "git rev-list origin/$rev $args".execute()
                lines = process.inputStream.bufferedReader().readLines()
            } catch (e: Exception) {
                lines = emptyList()
            }
        }

        process.destroy()

        val list = lines.map { it.trim() }
        return list
    }

    private fun Process.text(): String = ProcessGroovyMethods.getText(this)

    private fun String.execute(waitFor: Boolean = true): Process {
        val process = ProcessGroovyMethods.execute(this, emptyArray<String>(), project.projectDir)
        if (waitFor) {
            process.waitFor()
        }
        return process
    }
}


/**
 * parses `git diff --shortstat`
 *
 * https://github.com/git/git/blob/69e6b9b4f4a91ce90f2c38ed2fa89686f8aff44f/diff.c#L1561
 */
internal fun parseShortStats(shortstat: String): LocalChanges {
    val parts = shortstat.split(",")

    var filesChanges = 0
    var additions = 0
    var deletions = 0

    parts.map { it.trim() }.forEach { part ->
        if (part.contains("changed")) {
            val matches: MatchResult? = "(\\d+).*".toRegex().find(part)
            if (matches != null && matches.groups.size >= 2) {
                filesChanges = matches.groupValues[1].toInt()
            }
        }
        if (part.contains("(+)")) {
            val matches: MatchResult? = "(\\d+).*".toRegex().find(part)
            if (matches != null && matches.groups.size >= 2) {
                additions = matches.groupValues[1].toInt()
            }
        }
        if (part.contains("(-)")) {
            val matches: MatchResult? = "(\\d+).*".toRegex().find(part)
            if (matches != null && matches.groups.size >= 2) {
                deletions = matches.groupValues[1].toInt()
            }
        }
    }

    return LocalChanges(filesChanges, additions, deletions)
}
