# Release Preparation

This document sets the rules for future releases. The mere presence of this file or a successful CI build does not create a release.

## Version format

- The app version follows SemVer: `X.Y.Z`.
- The fork's tag has the form `android-vX.Y.Z`, so that it does not collide with upstream tags.
- `versionCode` only ever increases; `versionName` matches `X.Y.Z`.
- A tag is created only on a verified commit from the protected main branch.

## Before a release

1. Sync the needed changes from `upstream` with a regular merge or a separate PR, without rewriting published history.
2. Make sure `git status` is clean and the submodules are at exactly the pinned revisions.
3. Update the app version, the changelog, the README, and `THIRD_PARTY_NOTICES.md` if dependencies changed.
4. Wait for lint, unit tests, and the debug build to pass in GitHub Actions.
5. Build the release APK from a clean checkout of the tag with recursive submodules and JDK 17.
6. Sign the APK with a separate release key and verify the signature with `apksigner verify --verbose --print-certs`.
7. Check installation and launch on a supported Android version.

## Signing key

- The keystore and its passwords are never added to Git, Actions artifacts, logs, or the source code APK archive.
- For automation, secrets are allowed only in the protected GitHub Environment `release` with manual approval by the owner.
- A workflow from a pull request, especially from someone else's fork, must not receive release secrets.
- The certificate fingerprint of the first stable release is saved in the release notes; subsequent APKs must have the same certificate.

## Publishing

1. Create an annotated tag `android-vX.Y.Z` (where possible, also sign it with the owner's GPG/SSH key).
2. Prepare a draft GitHub Release, without publishing it until a manual check.
3. Attach:
   - `zapret-mobile-X.Y.Z.apk`;
   - `zapret-mobile-X.Y.Z.apk.sha256`;
   - `zapret-mobile-X.Y.Z-source.tar.gz` including the contents of the submodules;
   - the SHA-256 of the corresponding source code archive;
   - a list of notable changes;
   - a link to the exact tag with the corresponding source code;
   - the submodule revisions and up-to-date license notices.
4. Download the attached APK back, and re-verify the signature and SHA-256.
5. Publish the Release and only then update the link on the website.

The `Android release` workflow runs only after a tag matching `android-v*` is pushed and creates a **draft** GitHub Release. Publishing the draft remains manual. A debug APK from CI must not be renamed to a stable one or attached to a Release.

Building and signing are performed in separate jobs on separate runners:

- `build` has only `contents: read` and does not receive the `release` Environment or signing secrets. It verifies the annotated tag, the version match, that the commit belongs to the main branch, and the exact submodule revisions. The corresponding source code archive is created before Gradle starts and includes the recursive submodules.
- `sign` receives the `release` Environment and `contents: write`, but does not perform a checkout and does not run build code from the repository. It downloads the candidate by the immutable artifact ID, verifies the exact set of files and their SHA-256, then restores the keystore and signs the APK.

Before handoff for signing, all packaged native `.so` files are checked: every ELF LOAD segment must have an alignment of at least 16 KiB. In the signing job, Build Tools `35.0.0` runs `zipalign -P 16`; the finished APK is re-verified with `zipalign` and `apksigner`. This verifies the APK format but does not replace running on a real device with a 16 KiB page size.

### Required GitHub Secrets

It is recommended to store the secrets in the protected Environment `release`:

- `ANDROID_SIGNING_KEYSTORE_BASE64` — the entire keystore in Base64 without line breaks;
- `ANDROID_SIGNING_KEYSTORE_PASSWORD` — the keystore password;
- `ANDROID_SIGNING_KEY_ALIAS` — the key alias;
- `ANDROID_SIGNING_KEY_PASSWORD` — the private key password.

If at least one secret is missing, the signing job terminates before signing and before creating the draft; the build job may complete successfully without these secrets. Empty placeholders must not be created.

