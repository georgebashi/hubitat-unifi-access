# Hubitat Package Manager publishing

HPM uses a [package manifest](../packageManifest.json) for the app and drivers and an [intermediate repository manifest](../repository.json) to list packages. The repository manifest points to the package manifest on `main`; the package manifest points to code at the immutable `v0.1.0` tag. The initial package requires Hubitat `2.5.1.183`, the firmware used for live validation. Other Hubitat versions have not been validated.

## Install before directory listing

After the public GitHub repository and `v0.1.0` tag exist, open Hubitat Package Manager and choose **Install → From a URL**. Enter:

```text
https://raw.githubusercontent.com/georgebashi/hubitat-unifi-access/main/packageManifest.json
```

HPM's **Settings → Add a Custom Repository** can instead use:

```text
https://raw.githubusercontent.com/georgebashi/hubitat-unifi-access/main/repository.json
```

Install the package, then create **UniFi Access** under **Apps → Add User App** and configure it using the [README](../README.md#install). HPM enables OAuth for the app code as specified in the package manifest. A previously installed manual copy can use HPM **Match Up** to associate its app and drivers with this package. Review the proposed matches before confirming; HPM cannot determine the version of manually installed code. Choose an immediate HPM update if you want to replace it with the tagged release.

## List in HPM's public directory

Once the public URLs above resolve and the HPM install succeeds, fork [HubitatCommunity/hubitat-packagerepositories](https://github.com/HubitatCommunity/hubitat-packagerepositories) and add this entry to its `repositories.json` `repositories` array:

```json
{
  "name": "georgebashi",
  "location": "https://raw.githubusercontent.com/georgebashi/hubitat-unifi-access/main/repository.json"
}
```

Submit a pull request to that upstream repository. The name must be unique in its directory. After the pull request is merged, users can find the package through HPM search or Browse by Tags. HPM's [developer guide](https://hubitatpackagemanager.hubitatcommunity.com/devs1.html), [intermediate manifest format](https://hubitatpackagemanager.hubitatcommunity.com/intermManifest.html), and [master manifest format](https://hubitatpackagemanager.hubitatcommunity.com/masterManifest.html) describe these steps. Valid categories and tags are listed in the [directory settings](https://github.com/HubitatCommunity/hubitat-packagerepositories/blob/master/settings.json).

## Future releases

For each release, keep every component's UUID, name, and namespace stable. Change `version`, `dateReleased`, and `releaseNotes` in the package manifest; change all source `location` URLs and `licenseFile` to the new immutable release tag. Publish that tag with all referenced files, then publish the updated package manifest on `main`. Keep the repository manifest URL stable so HPM can find updates. Do not mix package-level and per-component versions; HPM recommends Semantic Versioning for reliable update detection.
