import org.kohsuke.github.GitHub

/*
 * Create a pull request on GitHub.
 */
@NonCPS
void call(Map args) {
    final String sourceBranch = args.sourceBranch ?: 'master'
    final String upstreamBranch = args.upstreamBranch ?: 'master'
    final String message = args.message ?: ''
    String sourceOwner = ''
    GitHub handle = null

    if (args.token != null) {
        handle = GitHub.connectUsingOAuth(args.token)
        sourceOwner = args.sourceOwner ?: handle.myself.login
    } else {
        handle = GitHub.connectUsingPassword(args.username, args.password)
        sourceOwner = args.sourceOwner ?: args.username
    }

    handle.getRepository(args.upstreamProject)
        .createPullRequest(args.title,
                           "${sourceOwner}:${sourceBranch}",
                           upstreamBranch,
                           message)
}
