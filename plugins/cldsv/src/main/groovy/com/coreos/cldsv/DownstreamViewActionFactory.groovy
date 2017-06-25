package com.coreos.cldsv

import hudson.Extension
import hudson.model.Action
import hudson.model.Run
import jenkins.model.Jenkins
import jenkins.model.TransientActionFactory

@Extension
public class DownstreamViewActionFactory extends TransientActionFactory<Run> {
    @Override
    public Collection<? extends Action> createFor(Run build) {
        String testJobName = build.parent.fullName.split('/')[0] + '/manifest'
        if (Jenkins.instance.getItemByFullName(testJobName) == null)
            return Collections.emptySet()
        return Collections.singleton(new DownstreamViewAction(build))
    }

    @Override
    public Class<Run> type() { Run }
}
