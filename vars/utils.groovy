// Updates GitHub commit status visible on PRs and commits.
// state: 'pending' | 'success' | 'failure' | 'error'
// description: short message shown in the GitHub UI (max 140 chars)
// context: label for the check (e.g. 'Jenkins CI / Build', 'Jenkins CI / Trivy')
def updateCommitStatus(String state, String description, String context = 'Jenkins CI') {
    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        def repoUrl = sh(script: 'git remote get-url origin', returnStdout: true).trim()
        def repoPath = repoUrl.replaceAll(/.*github\.com[\/:]/, '').replaceAll(/\.git$/, '')

        withEnv([
            "COMMIT_STATE=${state}",
            "COMMIT_DESC=${description}",
            "COMMIT_CONTEXT=${context}",
            "REPO_PATH=${repoPath}",
            "COMMIT_SHA=${env.GIT_COMMIT}",
            "BUILD_LINK=${env.BUILD_URL}"
        ]) {
            sh '''
                jq -n \
                    --arg state   "$COMMIT_STATE" \
                    --arg url     "$BUILD_LINK" \
                    --arg desc    "$COMMIT_DESC" \
                    --arg context "$COMMIT_CONTEXT" \
                    '{state: $state, target_url: $url, description: $desc, context: $context}' \
                | curl -sf \
                       -X POST \
                       -H "Authorization: Bearer $GITHUB_TOKEN" \
                       -H "Accept: application/vnd.github+json" \
                       -H "Content-Type: application/json" \
                       -H "X-GitHub-Api-Version: 2022-11-28" \
                       --data @- \
                       "https://api.github.com/repos/$REPO_PATH/statuses/$COMMIT_SHA"
            '''
        }
    }
}

// Creates a Jira Cloud issue and returns the issue key (e.g. ROBO-42).
// Credentials: 'jira-url' (secret text), 'jira-creds' (user=email, pass=API token)
def createJiraTicket(String project, String component, String appVersion, String shortCommit) {
    def issueKey
    withCredentials([
        string(credentialsId: 'jira-url', variable: 'JIRA_URL'),
        usernamePassword(credentialsId: 'jira-creds', usernameVariable: 'JIRA_EMAIL', passwordVariable: 'JIRA_TOKEN')
    ]) {
        withEnv([
            "JIRA_PROJECT=${project}",
            "JIRA_SUMMARY=${component} ${appVersion} (${shortCommit}) ready for UAT",
            "JIRA_COMPONENT=${component}",
            "JIRA_VERSION=${appVersion}",
            "JIRA_COMMIT=${shortCommit}"
        ]) {
            issueKey = sh(
                script: '''
                    jq -n \
                        --arg project   "$JIRA_PROJECT" \
                        --arg summary   "$JIRA_SUMMARY" \
                        --arg version   "$JIRA_VERSION" \
                        --arg commit    "$JIRA_COMMIT" \
                        --arg component "$JIRA_COMPONENT" \
                        '{
                            fields: {
                                project:     { key: $project },
                                summary:     $summary,
                                issuetype:   { name: "Story" },
                                labels:      [$commit],
                                description: {
                                    type: "doc", version: 1,
                                    content: [{ type: "paragraph", content: [{ type: "text",
                                        text: ("Component: " + $component + " | Version: " + $version + " | Commit: " + $commit)
                                    }] }]
                                }
                            }
                        }' \
                    | curl -sf \
                           -X POST \
                           -u "$JIRA_EMAIL:$JIRA_TOKEN" \
                           -H "Content-Type: application/json" \
                           -H "Accept: application/json" \
                           --data @- \
                           "$JIRA_URL/rest/api/3/issue" \
                    | jq -r .key
                ''',
                returnStdout: true
            ).trim()
        }
    }
    echo "Jira ticket created: ${issueKey}"
    return issueKey
}

// Transitions a Jira issue to a named status (e.g. 'UAT Success', 'Done').
// Looks up the transition ID by name — no hardcoded IDs needed.
def transitionJiraTicket(String issueKey, String transitionName) {
    withCredentials([
        string(credentialsId: 'jira-url', variable: 'JIRA_URL'),
        usernamePassword(credentialsId: 'jira-creds', usernameVariable: 'JIRA_EMAIL', passwordVariable: 'JIRA_TOKEN')
    ]) {
        withEnv([
            "ISSUE_KEY=${issueKey}",
            "TRANSITION_NAME=${transitionName}"
        ]) {
            sh '''
                TRANSITION_ID=$(curl -sf \
                    -u "$JIRA_EMAIL:$JIRA_TOKEN" \
                    -H "Accept: application/json" \
                    "$JIRA_URL/rest/api/3/issue/$ISSUE_KEY/transitions" \
                    | jq -r --arg name "$TRANSITION_NAME" \
                             '.transitions[] | select(.to.name == $name) | .id')

                if [ -z "$TRANSITION_ID" ]; then
                    echo "ERROR: no transition leading to status '$TRANSITION_NAME' found on $ISSUE_KEY"
                    exit 1
                fi

                jq -n --arg id "$TRANSITION_ID" '{ transition: { id: $id } }' \
                | curl -sf \
                       -X POST \
                       -u "$JIRA_EMAIL:$JIRA_TOKEN" \
                       -H "Content-Type: application/json" \
                       --data @- \
                       "$JIRA_URL/rest/api/3/issue/$ISSUE_KEY/transitions"

                echo "Transitioned $ISSUE_KEY to '$TRANSITION_NAME'"
            '''
        }
    }
}
