package com.ffvpn.client.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubUpdatesTest {
    @Test
    fun compareVersionsComparesComponentsNumerically() {
        assertTrue(GithubUpdates.compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(GithubUpdates.compareVersions("2.0.0", "1.99.99") > 0)
        assertTrue(GithubUpdates.compareVersions("1.2.10", "1.2.9") > 0)
    }

    @Test
    fun compareVersionsReturnsZeroForEqualVersions() {
        assertEquals(0, GithubUpdates.compareVersions("1.10.0", "1.10.0"))
        assertEquals(0, GithubUpdates.compareVersions("0.0.0", "0.0.0"))
    }

    @Test
    fun compareVersionsIdentifiesOlderVersions() {
        assertTrue(GithubUpdates.compareVersions("1.9.0", "1.10.0") < 0)
        assertTrue(GithubUpdates.compareVersions("1.99.99", "2.0.0") < 0)
        assertTrue(GithubUpdates.compareVersions("1.2.9", "1.2.10") < 0)
    }

    @Test
    fun compareVersionsRejectsInvalidVersionsOnEitherSide() {
        listOf("", "1", "1.2", "1.2.3.4", "v1.2.3", "1.2.x", "-1.2.3", "1.2.3-beta", " 1.2.3")
            .forEach { invalid ->
                expectFailure<IllegalArgumentException> {
                    GithubUpdates.compareVersions(invalid, "1.2.3")
                }
                expectFailure<IllegalArgumentException> {
                    GithubUpdates.compareVersions("1.2.3", invalid)
                }
            }
    }

    @Test
    fun compareVersionsRejectsOverflowInEveryComponent() {
        listOf("9223372036854775808.0.0", "0.9223372036854775808.0", "0.0.9223372036854775808")
            .forEach { overflow ->
                expectFailure<NumberFormatException> {
                    GithubUpdates.compareVersions(overflow, "1.2.3")
                }
                expectFailure<NumberFormatException> {
                    GithubUpdates.compareVersions("1.2.3", overflow)
                }
            }
    }

    @Test
    fun compareVersionsSupportsLongComponentsWithoutSubtractionOverflow() {
        assertTrue(GithubUpdates.compareVersions("9223372036854775807.0.0", "0.0.0") > 0)
        assertTrue(GithubUpdates.compareVersions("0.0.0", "9223372036854775807.0.0") < 0)
    }

    @Test
    fun rollingLatestPrereleaseAcceptsUploadedNonemptyApk() {
        assertEquals(listOf(GithubUpdates.Release("1.10.0", "latest")), candidates(release()))
    }

    @Test
    fun rollingLatestIgnoresAssetsThatAreNotUploaded() {
        val assets = listOf("new", "starter", "uploading", "Uploaded", "").map { asset(state = it) }
        assertTrue(candidates(release(assets = assets)).isEmpty())
    }

    @Test
    fun rollingLatestIgnoresZeroAndNegativeSizeAssets() {
        assertTrue(candidates(release(assets = listOf(asset(size = 0), asset(size = -1)))).isEmpty())
    }

    @Test
    fun rollingLatestFiltersBadAssetsWithoutDiscardingValidAsset() {
        val assets = listOf(
            asset(state = "starter"),
            asset(size = 0),
            asset(name = "NiloofarVPN-1.10.0-armeabi-v7a.apk"),
            asset(name = "other.apk"),
            asset(size = 1)
        )
        assertEquals(
            listOf(GithubUpdates.Release("1.10.0", "latest")),
            candidates(release(assets = assets))
        )
    }

    @Test
    fun exactArmAbiAssetsAreAcceptedForSupportedAbi() {
        listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
            assertEquals(
                listOf(GithubUpdates.Release("1.10.0", "latest")),
                candidates(release(assets = listOf(asset(name = "NiloofarVPN-1.10.0-$abi.apk"))), supportedAbis = listOf(abi))
            )
        }
    }

    @Test
    fun universalAssetsAreAcceptedForEitherArmAbi() {
        listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
            assertEquals(
                listOf(GithubUpdates.Release("1.10.0", "latest")),
                candidates(release(assets = listOf(asset(name = "NiloofarVPN-1.10.0-universal.apk"))), supportedAbis = listOf(abi))
            )
        }
    }

    @Test
    fun stableTagsWithAndWithoutVAcceptMatchingAssetVersion() {
        listOf("1.10.0", "v1.10.0").forEach { tag ->
            assertEquals(
                listOf(GithubUpdates.Release("1.10.0", tag)),
                candidates(release(tag = tag, prerelease = false))
            )
        }
    }

    @Test
    fun stableTagsWithAndWithoutVRejectMismatchedAssetVersion() {
        listOf("1.9.0", "v1.9.0").forEach { tag ->
            assertTrue(candidates(release(tag = tag, prerelease = false)).isEmpty())
        }
    }

    @Test
    fun draftsAndUnrelatedPrereleasesAreIgnored() {
        assertTrue(
            candidates(
                release(draft = true),
                release(tag = "v1.10.0", prerelease = false, draft = true),
                release(tag = "v1.10.0", prerelease = true),
                release(tag = "1.10.0", prerelease = true),
                release(tag = "nightly", prerelease = true),
                release(tag = "v1.10.0-beta", prerelease = true)
            ).isEmpty()
        )
    }

    @Test
    fun unrelatedStableTagsAreIgnored() {
        listOf("nightly", "release-1.10.0", "V1.10.0", "v1.10", "v1.10.0.1").forEach { tag ->
            assertTrue(tag, candidates(release(tag = tag, prerelease = false)).isEmpty())
        }
    }

    @Test
    fun wrongAssetFilenamesAreIgnored() {
        listOf(
            "niloofarvpn-1.10.0-arm64-v8a.apk",
            "OtherApp-1.10.0-arm64-v8a.apk",
            "prefix-NiloofarVPN-1.10.0-arm64-v8a.apk",
            "NiloofarVPN-v1.10.0-arm64-v8a.apk",
            "NiloofarVPN-1.10-arm64-v8a.apk",
            "NiloofarVPN-1.10.0-beta-arm64-v8a.apk",
            "NiloofarVPN-1.10.0-arm64-v8a.apk.asc",
            "NiloofarVPN-1.10.0-arm64-v8a.APK",
            "NiloofarVPN-1.10.0-x86.apk",
            "NiloofarVPN-1.10.0-x86_64.apk",
            "NiloofarVPN-1.10.0.apk"
        ).forEach { name ->
            assertTrue(name, candidates(release(assets = listOf(asset(name = name)))).isEmpty())
        }
    }

    @Test
    fun unsupportedArmAbiAssetsAreIgnored() {
        assertTrue(candidates(release(), supportedAbis = listOf("armeabi-v7a")).isEmpty())
        assertTrue(
            candidates(release(assets = listOf(asset(name = "NiloofarVPN-1.10.0-armeabi-v7a.apk")))).isEmpty()
        )
    }

    @Test
    fun universalAndArmAssetsDoNotMatchX86OrUnknownDevices() {
        val assets = listOf(
            asset(),
            asset(name = "NiloofarVPN-1.10.0-armeabi-v7a.apk"),
            asset(name = "NiloofarVPN-1.10.0-universal.apk"),
            asset(name = "NiloofarVPN-1.10.0-x86.apk"),
            asset(name = "NiloofarVPN-1.10.0-x86_64.apk")
        )
        listOf(listOf("x86"), listOf("x86_64"), listOf("x86_64", "x86"), listOf("riscv64"), emptyList())
            .forEach { abis ->
                assertTrue(abis.toString(), candidates(release(assets = assets), supportedAbis = abis).isEmpty())
            }
    }

    @Test
    fun emptyReleaseAndAssetListsHaveNoCandidates() {
        assertTrue(candidates().isEmpty())
        assertTrue(candidates(release(assets = emptyList())).isEmpty())
    }

    @Test
    fun missingAssetsOrRequiredAssetMetadataThrows() {
        expectFailure<JSONException> {
            candidates(release().apply { remove("assets") })
        }
        listOf("name", "state", "size").forEach { field ->
            expectFailure<JSONException> {
                candidates(release(assets = listOf(asset().apply { remove(field) })))
            }
        }
    }

    @Test
    fun maliciousTagsCannotProduceAnUntrustedReleasePage() {
        val maliciousTags = listOf(
            "https://evil.example/download",
            "//evil.example/download",
            "../../../../evil",
            "latest/../../../../evil",
            "v1.10.0/../../../../evil",
            "v1.10.0%2f..%2f..",
            "v1.10.0?redirect=https://evil.example",
            "v1.10.0#https://evil.example"
        )
        maliciousTags.forEach { tag ->
            assertTrue(tag, candidates(release(tag = tag, prerelease = false)).isEmpty())
        }
        val trustedPage = "https://github.com/FossilizedProgrammer/niloofar_vpn/releases/tag/v1.10.0"
        val accepted = candidates(
            release(tag = "v1.10.0", prerelease = false).put("html_url", "https://evil.example/download")
        )
        assertEquals(listOf(GithubUpdates.Release("1.10.0", "v1.10.0")), accepted)
        assertEquals(trustedPage, accepted.single().page)
    }

    @Test
    fun malformedJsonThrowsInsteadOfReturningSuccessfulEmptyResult() {
        listOf("", "not JSON", "[{", "{}", "null", "[null]", "[1]").forEach { json ->
            expectFailure<JSONException> {
                GithubUpdates.candidates(json, listOf("arm64-v8a"))
            }
        }
    }

    @Test
    fun overflowingAssetVersionThrowsInsteadOfBecomingCandidate() {
        expectFailure<NumberFormatException> {
            candidates(release(assets = listOf(asset(name = "NiloofarVPN-9223372036854775808.0.0-arm64-v8a.apk"))))
        }
    }

    private fun candidates(
        vararg releases: JSONObject,
        supportedAbis: List<String> = listOf("arm64-v8a")
    ): List<GithubUpdates.Release> = GithubUpdates.candidates(JSONArray(releases.toList()).toString(), supportedAbis)

    private fun release(
        tag: String = "latest",
        prerelease: Boolean = true,
        draft: Boolean = false,
        assets: List<JSONObject> = listOf(asset())
    ): JSONObject = JSONObject()
        .put("tag_name", tag)
        .put("prerelease", prerelease)
        .put("draft", draft)
        .put("assets", JSONArray(assets))

    private fun asset(
        name: String = "NiloofarVPN-1.10.0-arm64-v8a.apk",
        state: String = "uploaded",
        size: Long = 1024
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("state", state)
        .put("size", size)

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return
            throw AssertionError("Expected ${T::class.java.name}, got ${failure.javaClass.name}", failure)
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown")
    }
}
