# Releasing ﬀorj

Releases are tag-driven: pushing a `vX.Y.Z` tag runs `.github/workflows/release.yml`,
which builds, signs, publishes `X.Y.Z` to Maven Central (via the Sonatype Central
Portal), and creates a GitHub release. The version in `gradle.properties` stays a
`-SNAPSHOT`; the workflow overrides it from the tag.

## One-time setup (manual, account owner only)

### 1. Central Portal account + namespace

1. Create an account at <https://central.sonatype.com> (sign in with GitHub is fine).
2. Register the namespace `dev.fforj` (Publish → Namespaces → Add Namespace).
3. Verify it: the portal gives you a verification key; add it as a **DNS TXT record**
   on `fforj.dev` at your registrar. Verification usually completes within minutes;
   the TXT record can be deleted afterwards.
4. Generate a **user token** (account icon → View Account → Generate User Token).
   This yields a username + password pair — these are the publishing credentials,
   not your portal login.

### 2. GPG signing key

```sh
gpg --full-generate-key            # RSA 4096, no expiry (or your policy), identity: your email
gpg --list-secret-keys --keyid-format long   # note the key id (after rsa4096/)

# Maven Central verifies signatures against public keyservers — publish the public key:
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# Export the private key for CI (ASCII-armored, goes into a GitHub secret):
gpg --export-secret-keys --armor <KEY_ID>
```

### 3. GitHub secrets

Repo → Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | user-token username from step 1.4 |
| `MAVEN_CENTRAL_PASSWORD` | user-token password from step 1.4 |
| `SIGNING_KEY` | the full ASCII-armored private key block from step 2 |
| `SIGNING_KEY_PASSWORD` | the key's passphrase |

## Cutting a release

```sh
# From a green main:
git tag v0.1.0
git push origin v0.1.0
```

That's it. The workflow refuses `-SNAPSHOT` tags, publishes with automatic release
(no manual "publish" click in the portal), and the artifact appears on Maven Central
after validation — typically within ~15 minutes, searchable within the hour.

After a release, bump `version` in `gradle.properties` to the next `-SNAPSHOT` on
`main`.

## Local dry run

```sh
./gradlew publishToMavenLocal     # builds the exact publication into ~/.m2 (unsigned)
```
