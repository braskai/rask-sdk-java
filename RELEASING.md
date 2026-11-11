# Releasing

A release is a GitHub Release. Publishing one sends the artifact to Maven Central from CI.

1. In a pull request, set the new version in `pom.xml` and `SdkVersion.java` (a test keeps them equal). Merge it.
2. Publish a GitHub Release whose tag is `v` plus that version. Without `--target`, the tag goes on the default branch:

   ```sh
   gh release create v3.0.0-alpha.1 --generate-notes --prerelease
   ```

3. The `Release` workflow runs the CI checks on the tag, builds the jar with its sources and javadoc, signs them, and
   publishes `ai.rask:rask-sdk`. It waits until Central has published the version.

The workflow refuses a tag that is not the version in `pom.xml`, and a tag that is not on the default branch.
A version on Maven Central cannot be replaced or removed: to fix one, release the next.

## Set up once

Maven Central asks for an account, a verified namespace and a signing key. Until the four secrets below exist, the
workflow fails at its publish step and publishes nothing.

1. Sign in at [central.sonatype.com](https://central.sonatype.com) and add the namespace `ai.rask`. Central shows a TXT
   record to put on `rask.ai`, and verifies it.
2. There, generate a user token. Its two halves are the secrets `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`.
3. Make a signing key, and send its public half to a key server Central reads:

   ```sh
   gpg --quick-generate-key "Brask Inc. <an address Brask reads>" rsa4096 sign 5y
   gpg --keyserver keyserver.ubuntu.com --send-keys <key id>
   ```

4. Put the secrets into the environment `maven-central` of this repository. `gh` asks for each value, so none of them
   lands in the shell history:

   ```sh
   gh secret set CENTRAL_USERNAME --env maven-central
   gh secret set CENTRAL_PASSWORD --env maven-central
   gh secret set GPG_PASSPHRASE --env maven-central
   gpg --armor --export-secret-keys <key id> | gh secret set GPG_PRIVATE_KEY --env maven-central
   ```

The environment `maven-central` takes only `v*` tags, so no other run can read the secrets.
