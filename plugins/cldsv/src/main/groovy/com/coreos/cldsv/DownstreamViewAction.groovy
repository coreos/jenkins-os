package com.coreos.cldsv

import hudson.model.Action
import hudson.model.Cause
import hudson.model.ParametersAction
import hudson.model.Run
import jenkins.model.Jenkins

public class DownstreamViewAction implements Action {
    private Run build

    final String displayName = 'Downstream View'
    final String iconFileName = '/plugin/coreos-cldsv/cl.png'
    final String urlName = 'cldsv'

    public DownstreamViewAction(Run build) {
        this.build = build
    }

    public Run getBuild() { this.build }

    public Map getBuildOrigin() {
        def builds = [:].withDefault { [].withDefault { [downstreams: [], params: [:]] } }
        Jenkins.instance.getItemByFullName('os').allJobs.each { job ->
            job.builds.each { build ->
                builds[job.fullName][build.number].run = build

                build.getActions(ParametersAction)?.each { action ->
                    action.parameters?.each { param ->
                        builds[job.fullName][build.number].params[param.name] = param.value
                    }
                }

                Run up = build.getCause(Cause.UpstreamCause)?.upstreamRun
                if (up == null)
                    return

                builds[up.parent.fullName][up.number].downstreams += [builds[job.fullName][build.number]]
                builds[job.fullName][build.number].upstream = builds[up.parent.fullName][up.number]
            }
        }

        def origin = builds[this.build.parent.fullName][this.build.number]
        while (origin?.upstream != null)
            origin = origin.upstream
        return origin?.run == null ? null : origin
    }
}
