# Releasing

A release is one run of the `Release` workflow. It publishes the artifact to Maven Central from CI.

1. In a pull request, set the new version in `pom.xml` and `SdkVersion.java` (a test keeps them equal). Merge it.
2. Start the workflow on the default branch, with "Run workflow" on the Actions tab or from a terminal:

   ```sh
   gh workflow run release.yml
   ```

3. The workflow runs the CI checks, builds the jar with its sources and javadoc, signs them, and publishes
   `ai.rask:rask-sdk`. It waits until Central has published the version, then tags the commit `v` plus the version and
   makes the GitHub Release.

The workflow refuses to run from any other branch, and refuses a version that is already tagged or already on Central.
A version on Maven Central cannot be replaced or removed: to fix one, release the next.
If a run fails after Central took the version, make the release by hand:
`gh release create v<version> --target <the commit> --generate-notes`, with `--prerelease` for a pre-release version.

## Set up once

Maven Central asks for an account, a verified namespace and a signing key. Until the four secrets below exist, the
workflow fails at its publish step and publishes nothing.

1. Check the environment `maven-central` of this repository before any secret goes into it (Settings → Environments →
   `maven-central` → Deployment branches and tags): selected branches and tags, one rule, the default branch. That is
   what keeps a run on any other branch from reading the secrets. If the default branch is renamed, change the rule
   with it.
2. Sign in at [central.sonatype.com](https://central.sonatype.com) and add the namespace `ai.rask`. Central shows a TXT
   record to put on `rask.ai`, and verifies it.
3. There, generate a user token. Its two halves are the secrets `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`.
4. Make a signing key, and send its public half to a key server Central reads:

   ```sh
   gpg --quick-generate-key "Brask Inc. <an address Brask reads>" rsa4096 sign 5y
   gpg --keyserver keyserver.ubuntu.com --send-keys <key id>
   ```

5. Put the secrets into the environment. `gh` asks for each value, so none of them lands in the shell history:

   ```sh
   gh secret set CENTRAL_USERNAME --env maven-central
   gh secret set CENTRAL_PASSWORD --env maven-central
   gh secret set GPG_PASSPHRASE --env maven-central
   gpg --armor --export-secret-keys <key id> | gh secret set GPG_PRIVATE_KEY --env maven-central
   ```
