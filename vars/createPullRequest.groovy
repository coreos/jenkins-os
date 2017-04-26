import org.kohsuke.github.GitHub

/*
 * Create a pull request on GitHub.
 */
@NonCPS
void call(Map args) {
    final String sourceOwner = args.sourceOwner ?: args.username
    final String sourceBranch = args.sourceBranch ?: 'master'
    final String upstreamBranch = args.upstreamBranch ?: 'master'
    final String message = args.message ?: ''

    GitHub.connectUsingPassword(args.username, args.password)
        .getRepository(args.upstreamProject)
        .createPullRequest(args.title,
                           "${sourceOwner}:${sourceBranch}",
                           upstreamBranch,
                           message)
}
