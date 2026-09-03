package audiolens.pycharm.compat

import com.intellij.openapi.project.Project
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Bridges the trusted-project API transition between platform branches 251 and 252.
 *
 * Branch 251 only exposes an internal LocatedProject overload, while 252 and newer expose
 * isProjectTrusted(Project). Resolving the methods lazily keeps one plugin binary loadable on
 * both sides of that transition. Failure is deliberately treated as untrusted.
 */
object ProjectTrust {
    private const val TRUSTED_PROJECTS = "com.intellij.ide.trustedProjects.TrustedProjects"
    private const val TRUSTED_PROJECTS_LOCATOR =
        "com.intellij.ide.trustedProjects.TrustedProjectsLocator"

    private val resolver: (Project) -> Boolean by lazy(::createResolver)

    fun isTrusted(project: Project): Boolean =
        runCatching { resolver(project) }.getOrDefault(false)

    private fun createResolver(): (Project) -> Boolean {
        val trustedProjectsClass = Class.forName(TRUSTED_PROJECTS)

        trustedProjectsClass.methods
            .firstOrNull { method ->
                method.name == "isProjectTrusted" &&
                    Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.contentEquals(arrayOf(Project::class.java))
            }
            ?.let { method ->
                return { project -> method.invokeBoolean(receiver = null, argument = project) }
            }

        val locatorClass = Class.forName(TRUSTED_PROJECTS_LOCATOR)
        val locatedProjectClass = Class.forName("$TRUSTED_PROJECTS_LOCATOR\$LocatedProject")
        val locatorCompanion = locatorClass.getField("Companion").get(null)
        val locateProject = locatorCompanion.javaClass.getMethod("locateProject", Project::class.java)
        val trustedProjectsInstance = trustedProjectsClass.getField("INSTANCE").get(null)
        val isProjectTrusted =
            trustedProjectsClass.getMethod("isProjectTrusted", locatedProjectClass)

        return { project ->
            val locatedProject = locateProject.invoke(locatorCompanion, project)
            isProjectTrusted.invokeBoolean(trustedProjectsInstance, locatedProject)
        }
    }

    private fun Method.invokeBoolean(receiver: Any?, argument: Any): Boolean =
        invoke(receiver, argument) as Boolean
}
