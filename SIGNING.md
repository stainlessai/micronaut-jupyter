# Codesigning mini-tutorial
This is a small tutorial showing the steps required to sign the code using a private key, and a mechanism that is compatible with Travis and Jitpack.

## 1. You need a keypair
You need GnuGPG2 installed on your system, and either a new key or a valid existing key. You will also need to remember your passphrase.
### 1a. New Key
```
$ gpg --full-generate-key
```
Now follow steps in `Existing key`, below
### 1b. Existing Key
```
$ gpg --list-keys
$USER_HOME/.gnupg/pubring.gpg
------------------------------------
pub   rsa2048 2019-11-04 [SC] [expires: 2021-11-03]
      XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXABCDEFGH
uid           [ultimate] Joe Bloggs <joe.bloggs@bloggs.net>
sub   rsa2048 2019-11-04 [E] [expires: 2021-11-03]
```
The key short code in this case is `ABCDEFGH` (the last 8 characters)

## 2. Export key so that Gradle can read it
```
gpg --armor --export-secret-key ABCDEFGH >> SIGNING_GPG_KEY
```
You will be asked for your passphrase.

__*UNDER NO CIRCUMSTANCES SHOULD YOU CHECK THIS FILE IN TO GITHUB OR PUBISH IT IN ANY WAY*__

## 3. Set up build 
```
export SIGNING_GPG_PASSWORD=<your passphrase>
```
## 4. Build
```
gradle build
```