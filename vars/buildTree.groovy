import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

/*
 * Return a plain text tree of downstream job states.
 */
@NonCPS
String call(RunWrapper topRunWrapper) {
    def builds = [:].withDefault { [].withDefault { [downstreams: []] } }
    def icons = [:].withDefault { ':question:' }
    icons['BUILDING'] = ':gear:'
    icons['FAILURE'] = ':trashfire:'
    icons['SUCCESS'] = ':white_check_mark:'
    icons['UNSTABLE'] = ':warning:'

    /*
     * This was just taken from another script that spiders through all
     * builds and constructs a graph out of them (including rebuilds).
     * There really should be a more efficient query method used here.
     */
    Jenkins.instance.getAllItems(WorkflowJob).each { job ->
        job.builds.each { build -> /* WorkflowRun */
            builds[job.fullName][build.number].run = build

            def upBuild = build.getCause(Cause.UpstreamCause)?.upstreamRun
            if (upBuild != null) {
                String upName = upBuild.parent.fullName
                int upNumber = upBuild.number
                builds[upName][upNumber].downstreams += [build]
            }
        }
    }

    Closure makeTree = null  /* Declare it beforehand for recursion.  */
    makeTree = { build, indent ->
        String result = "${build.result ?: 'BUILDING'}"
        String name = build.parent.fullName
        String tag = ''
        String url = "${Jenkins.instance.rootUrl}${build.url}consoleFull"
        int number = build.number

        def params = [:]
        build.getActions(ParametersAction)?.each { action ->
            action.parameters?.each {
                params[it.name] = it.value
            }
        }

        if (name == 'os/manifest')
            tag = " (${params.MANIFEST_REF})"
        else if (params.BOARD != null)
            tag = " (${params.BOARD})"

        String output = "${indent}${icons[result]} ${name}${tag} ${result}"
        output += "\n${indent}${url}"
        builds[name][number].downstreams.sort {
            String.format('%s%08X', it.parent.fullName, it.number)
        } each {
            output += '\n' + makeTree(it, "${indent}    ")
        }
        return output
    }

    return makeTree(topRunWrapper.rawBuild, '')
}
