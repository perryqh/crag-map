package com.perryhertler.cragmap

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests — need a real device/emulator, unlike the Robolectric
 * unit tests: a real Compose semantics tree over MainActivity, the real
 * bundled devils_lake.db read via Room/FTS on-device, and the real runtime
 * location-permission flow. This was the "no androidTest coverage of the
 * map/UI itself yet" gap called out in the README, unblocked once an AVD was
 * set up (this app's map rendering itself is a native GL surface with no
 * Compose semantics, so what's covered here is the Compose-driven layer on
 * top of it: search, the area/climb sheet, and edit mode).
 *
 * ACCESS_FINE_LOCATION is pre-granted so MapScreen's permission launcher
 * never pops a system dialog over the Compose UI mid-test.
 */
@RunWith(AndroidJUnit4::class)
class MapScreenInstrumentedTest {

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesAndShowsSearchBar() {
        composeRule.onNodeWithTag("search-field").assertIsDisplayed()
    }

    @Test
    fun searchingAnAreaOpensItsChildSheet() {
        // Matches two areas by substring ("East Bluff 04 - East Rampart" and
        // "08. East Rampart") but only one result row's text contains the
        // full name below, so the click target stays unambiguous.
        composeRule.onNodeWithTag("search-field").performTextInput("East Rampart")

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("East Bluff 04 - East Rampart", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("East Bluff 04 - East Rampart", substring = true)).performClick()

        // The sheet header re-shows the same area name once it opens; the
        // search results list is gone by then (query cleared on selection).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("East Bluff 04 - East Rampart", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("East Bluff 04 - East Rampart", substring = true)).assertIsDisplayed()
    }

    @Test
    fun searchingAClimbShowsItsGradeAndSafetyRating() {
        // "The Beast" (5.4, trad, rated R) — real bundled data, confirmed
        // unique. Exercises the OpenBeta safety-rating badge from Schema.kt.
        composeRule.onNodeWithTag("search-field").performTextInput("The Beast")

        // Match on the grade, not the climb name — the typed query itself is
        // "The Beast" too, and the text field's own EditableText would
        // otherwise collide with the search-result row for that matcher.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("5.4", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("5.4", substring = true)).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("The Beast", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("The Beast", substring = true))
            .assertTextContains("5.4", substring = true)
            .assertTextContains("R", substring = true)
    }

    @Test
    fun editModeShowsFieldSurveyBannerOnceASheetIsOpen() {
        composeRule.onNodeWithContentDescription("Toggle field-survey edit mode").performClick()

        composeRule.onNodeWithTag("search-field").performTextInput("East Rampart")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("East Bluff 04 - East Rampart", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("East Bluff 04 - East Rampart", substring = true)).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("EDIT MODE", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("EDIT MODE — field survey capture").assertIsDisplayed()
    }

    @Test
    fun editModeShowsPhotoCaptureButtonsForAreaAndClimbs() {
        // Covers the photo-capture UI added from the blueprint's "assign a
        // photo to multiple climbs" discussion — not the actual camera round
        // trip (that delegates to an external camera app via an implicit
        // intent, too fragile/device-dependent to automate here), just that
        // the capture affordances exist once Edit Mode is on and a climb
        // sheet (not just an area's child list) is open.
        composeRule.onNodeWithContentDescription("Toggle field-survey edit mode").performClick()

        composeRule.onNodeWithTag("search-field").performTextInput("The Beast")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("5.4", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("5.4", substring = true)).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("Take a photo of", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("Take a photo of", substring = true)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Take a photo of The Beast").assertIsDisplayed()
    }
}
