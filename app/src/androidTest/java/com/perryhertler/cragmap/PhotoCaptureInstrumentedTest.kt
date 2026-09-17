package com.perryhertler.cragmap

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.perryhertler.cragmap.data.PinOverrideDatabase
import com.perryhertler.cragmap.data.deletePhotoAndTargets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Automates the photo-capture round trip end to end — tap a capture button,
 * the app launches the camera app's implicit intent, a result comes back,
 * and our own code reacts (saves directly, or offers the multi-climb tag
 * sheet first) — without depending on whatever camera app happens to be on
 * the test device or its UI, which would make this fragile/flaky across
 * emulator images and vendors. Espresso-Intents intercepts the
 * ACTION_IMAGE_CAPTURE intent before it ever reaches the Android framework
 * and hands back a canned RESULT_OK, exactly like MapScreen.kt's
 * ActivityResultContracts.TakePicture() launcher would see from a real
 * camera app that captured successfully.
 *
 * What's intentionally NOT simulated: the camera app actually writing JPEG
 * bytes to the destination Uri. Nothing in the round trip being tested here
 * (DB insert, tag sheet, Review list) reads those bytes — only a thumbnail
 * decode would, and that already degrades to "no image" rather than
 * crashing on a missing/empty file (see AssetThumbnail/LocalPhotoThumbnail).
 */
@RunWith(AndroidJUnit4::class)
class PhotoCaptureInstrumentedTest {

    // PinOverrideDatabase is a process-lifetime singleton (see its
    // getInstance()), so rows captured by one test method are still there
    // for the next one in the same instrumentation run — MainActivity would
    // read that leftover overrideCount/photoCount into its very first
    // composition. Must run BEFORE composeRule launches the Activity (hence
    // order = 0, outermost), not in a plain @Before, which JUnit runs AFTER
    // rule setup — i.e. after the Activity (and its stale first read) already
    // exists.
    @get:Rule(order = 0)
    val clearOverrideDbRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val db = PinOverrideDatabase.getInstance(context)
                runBlocking {
                    db.pinOverrideDao().all().forEach { db.pinOverrideDao().delete(it.targetUuid) }
                    db.photoOverrideDao().allPhotos().forEach { db.photoOverrideDao().deletePhotoAndTargets(it.id) }
                }
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION)

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun stubCameraIntent() {
        Intents.init()
        Intents.intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun releaseIntents() {
        Intents.release()
    }

    private fun openEditMode() {
        composeRule.onNodeWithContentDescription("Toggle field-survey edit mode").performClick()
    }

    private fun searchAndClick(query: String, clickMatchSubstring: String) {
        composeRule.onNodeWithTag("search-field").performTextInput(query)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText(clickMatchSubstring, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText(clickMatchSubstring, substring = true)).performClick()
        // The search field's keyboard is still up at this point; leaving it
        // open doesn't block subsequent programmatic performClick() calls,
        // but closing it keeps what's on screen predictable for any later
        // screenshot/tree-dump debugging.
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
    }

    @Test
    fun capturingAnAreaPhotoWithNoClimbsInTheSheetSavesImmediately() {
        // "East Bluff 04 - East Rampart" is a disclosure node (sub-areas, no
        // climbs of its own), so there's no candidate list to tag against —
        // the photo should save the instant the stubbed camera returns.
        openEditMode()
        searchAndClick("East Rampart", "East Bluff 04 - East Rampart")

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("Take a photo of", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("Take a photo of", substring = true)).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("Review (1)", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("Review (1)", substring = true)).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("East Bluff 04 - East Rampart", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Captured pins (0) · photos (1)").assertIsDisplayed()
    }

    @Test
    fun capturingAClimbPhotoOffersToTagOtherClimbsInTheSameSheet() {
        // "09. TCC" has exactly two climbs (TCC Right Arete, TCC Boulder) —
        // small enough that both rows are on-screen without scrolling.
        openEditMode()
        searchAndClick("TCC Right Arete", "V1")

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("TCC Boulder", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Take a photo of TCC Right Arete").performClick()

        // The tag sheet appears with the triggering climb pre-checked and its
        // sibling offered as an extra tag — the motivating case from the
        // blueprint discussion (routes a few meters apart on one wall).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("Which climbs does this photo show?", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Disambiguated by tag: the still-open climb sheet behind the tag
        // sheet also shows "TCC Boulder" as plain text, so a text matcher
        // alone would hit both. "fddf7ff6-a53c-59ba-ba93-f3b943eee239" is
        // TCC Boulder's real, stable OpenBeta uuid in the bundled db.
        composeRule.onNodeWithTag("photo-tag-fddf7ff6-a53c-59ba-ba93-f3b943eee239").performClick()
        composeRule.onNodeWithText("Save").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("Review (1)", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("Review (1)", substring = true)).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("TCC Right Arete, TCC Boulder", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("TCC Right Arete, TCC Boulder", substring = true)).assertIsDisplayed()
    }
}
