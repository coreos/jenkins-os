/*
 * Sends a notification to Slack.  If the Slack Notification Plugin is not
 * configured the the notification will be echoed to the Jenkins build log.
 */

def call(Map args) {
    Map colorMap = [
        'grey'    :'#C0C0C0',
        'persian' :'#2020C0',
    ]
    String channel = args.channel ?: ''
    String color = args.color ? colorMap.get(args.color, args.color) : 'good'

    try {
        slackSend(channel: channel, color: color, message: args.message)
    } catch (NoSuchMethodError err) {
        echo 'Slack Notification (' + args.color + '): ' + args.message
    }
}
