package fr.velogo.app.update

import fr.velogo.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lecture de la liste des Releases du dépôt. Les réponses ont la forme de celles de l'API de GitHub, relevée sur
 * la première version publiée (v0.5.11, le 21/11/2026) : seuls les champs sans intérêt ici ont été retirés.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateCheckerTest {

    private fun asset(name: String, release: String) = """
        {"url": "https://api.github.com/repos/exemple/velo/releases/assets/775232362", "id": 775232362,
         "name": "$name", "label": "", "content_type": "application/octet-stream", "state": "uploaded",
         "size": 15713160, "digest": "sha256:ae5aa99ed213728048d3b707bfe1f3d749b6d24027f8898ba9f27d6e90a79ec2",
         "download_count": 0, "created_at": "2026-11-21T10:39:59Z", "updated_at": "2026-11-21T10:40:00Z",
         "browser_download_url": "https://github.com/exemple/velo/releases/download/$release/$name"}
    """.trimIndent()

    private fun release(tag: String, assets: List<String>, draft: Boolean = false, prerelease: Boolean = false, body: String = "Notes de $tag") = """
        {"url": "https://api.github.com/repos/exemple/velo/releases/453616175",
         "html_url": "https://github.com/exemple/velo/releases/tag/$tag", "id": 453616175,
         "tag_name": "$tag", "target_commitish": "2f4fee35fd1deeed36329342751e67c4d90c5749", "name": "VeloGo",
         "draft": $draft, "immutable": false, "prerelease": $prerelease,
         "created_at": "2026-11-21T10:33:07Z", "updated_at": "2026-11-21T10:40:00Z", "published_at": "2026-11-21T10:40:00Z",
         "assets": [${assets.joinToString(",") { asset(it, tag) }}],
         "tarball_url": "https://api.github.com/repos/exemple/velo/tarball/$tag",
         "zipball_url": "https://api.github.com/repos/exemple/velo/zipball/$tag",
         "body": "$body"}
    """.trimIndent()

    private fun list(vararg releases: String) = releases.joinToString(",", "[", "]")

    /** La Release des cartes hors ligne : beaucoup de fichiers, aucun APK, une étiquette qui n'est pas une version. */
    private val maps = release(
        "cartes",
        listOf("catalogue.json", "dalle-E0_N42-7faf46876a.rd5", "fond-corse-0c1d2e3f4a.pmtiles", "relief-corse-5b6c7d8e9f.pmtiles"),
        prerelease = true,
    )

    @Test
    fun `la premiere version publiee, a cote des cartes`() {
        val notes = "## VeloGo 0.5.11\\n\\n- **Statistiques** par semaine, par mois ou par année.\\n"
        val found = UpdateChecker.parse(list(release("v0.5.11", listOf("VeloGo-0.5.11.apk"), body = notes), maps))!!

        assertEquals("0.5.11", found.versionName)
        assertEquals(11, found.buildNumber)
        assertEquals("https://github.com/exemple/velo/releases/download/v0.5.11/VeloGo-0.5.11.apk", found.apkUrl)
        assertEquals("https://github.com/exemple/velo/releases/tag/v0.5.11", found.pageUrl)
        assertTrue(found.notes.startsWith("## VeloGo 0.5.11\n\n- **Statistiques**"))
    }

    @Test
    fun `le plus grand numero de build l'emporte, quel que soit l'ordre`() {
        val json = list(
            release("v0.5.11", listOf("VeloGo-0.5.11.apk")),
            maps,
            release("v0.6.14", listOf("VeloGo-0.6.14.apk")),
            release("v0.5.12", listOf("VeloGo-0.5.12.apk")),
        )
        assertEquals("0.6.14", UpdateChecker.parse(json)!!.versionName)
    }

    @Test
    fun `brouillons, versions sans APK et etiquettes inconnues sont ignores`() {
        val json = list(
            release("v0.5.20", listOf("VeloGo-0.5.20.apk"), draft = true),
            release("v0.5.19", listOf("notes.txt")),
            release("v0.5.18", emptyList()),
            release("essai-0.5.17", listOf("VeloGo-0.5.17.apk")),
            release("v0.5.16-beta", listOf("VeloGo-0.5.16.apk")),
            release("v0.5.11", listOf("empreinte.txt", "VeloGo-0.5.11.apk")),
        )
        assertEquals(11, UpdateChecker.parse(json)!!.buildNumber)
    }

    @Test
    fun `rien a proposer`() {
        assertNull(UpdateChecker.parse(list(maps)))
        assertNull(UpdateChecker.parse("[]"))
    }

    @Test
    fun `reponses illisibles`() {
        assertNull(UpdateChecker.parse(""))
        assertNull(UpdateChecker.parse("<html><body>502 Bad Gateway</body></html>"))
        // Ce que GitHub répond quand trop de demandes sont parties de la même adresse.
        assertNull(UpdateChecker.parse("""{"message": "API rate limit exceeded for 203.0.113.7.", "documentation_url": "https://docs.github.com/rest"}"""))
        assertNull(UpdateChecker.parse("""[42, "texte", null]"""))
    }

    @Test
    fun `plus recente que l'appli installee, ou non`() {
        val installed = BuildConfig.VERSION_CODE
        fun found(build: Int) = UpdateChecker.parse(list(release("v0.5.$build", listOf("VeloGo-0.5.$build.apk"))))!!

        assertTrue(found(installed + 1).isNewer)
        assertFalse(found(installed).isNewer)
    }
}
