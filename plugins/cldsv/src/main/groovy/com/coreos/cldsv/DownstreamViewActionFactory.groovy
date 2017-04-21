package com.coreos.cldsv

import java.util.Collection
import java.util.Collections

import hudson.Extension
import hudson.model.Action
import hudson.model.Run
import jenkins.model.TransientActionFactory

@Extension
public class DownstreamViewActionFactory extends TransientActionFactory<Run> {
    @Override
    public Collection<? extends Action> createFor(Run build) {
        if (!build.parent.fullName.startsWith('os/'))
            return Collections.emptySet()
        return Collections.singleton(new DownstreamViewAction(build))
    }

    @Override
    public Class<Run> type() { Run }
}
