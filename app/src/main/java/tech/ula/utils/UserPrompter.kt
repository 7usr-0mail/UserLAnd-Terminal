package tech.ula.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.annotation.StringRes
import tech.ula.R

interface UserPrompter {
    @get: StringRes val initialPrompt: Int
    @get: StringRes val initialPosBtnText: Int
    @get: StringRes val initialNegBtnText: Int
    val altInitialPosFlow: Boolean
    val initialPositiveBtnAction: () -> Unit

    @get: StringRes val primaryRequest: Int
    @get: StringRes val primaryPosBtnText: Int
    @get: StringRes val primaryNegBtnText: Int
    val primaryPositiveBtnAction: () -> Unit

    @get: StringRes val secondaryRequest: Int
    @get: StringRes val secondaryPosBtnText: Int
    @get: StringRes val secondaryNegBtnText: Int
    val secondaryPositiveBtnAction: () -> Unit

    val finishedAction: () -> Unit

    val savedActivity: Activity
    val savedViewGroup: ViewGroup

    fun viewShouldBeShown(): Boolean
    fun showView() {
        val view = savedActivity.layoutInflater.inflate(R.layout.layout_user_prompt, null)
        val prompt = view.findViewById<TextView>(R.id.text_prompt)
        val posBtn = view.findViewById<Button>(R.id.btn_positive_response)
        val negBtn = view.findViewById<Button>(R.id.btn_negative_response)

        prompt.text = savedActivity.getString(initialPrompt)

        posBtn.text = savedActivity.getString(initialPosBtnText)
        posBtn.setOnClickListener initial@{
            if (altInitialPosFlow) {
                initialPositiveBtnAction()
                finishedAction()
                savedViewGroup.removeView(view)
            } else {
                prompt.text = savedActivity.getString(primaryRequest)

                posBtn.text = savedActivity.getString(primaryPosBtnText)
                posBtn.setOnClickListener primary@{
                    primaryPositiveBtnAction()
                    finishedAction()
                    savedViewGroup.removeView(view)
                }

                negBtn.text = savedActivity.getString(primaryNegBtnText)
                negBtn.setOnClickListener primary@{
                    finishedAction()
                    savedViewGroup.removeView(view)
                }
            }
        }

        negBtn.text = savedActivity.getString(initialNegBtnText)
        negBtn.setOnClickListener initial@{
            prompt.text = savedActivity.getString(secondaryRequest)

            posBtn.text = savedActivity.getString(secondaryPosBtnText)
            posBtn.setOnClickListener secondary@{
                secondaryPositiveBtnAction()
                finishedAction()
                savedViewGroup.removeView(view)
            }

            negBtn.text = savedActivity.getString(secondaryNegBtnText)
            negBtn.setOnClickListener secondary@{
                finishedAction()
                savedViewGroup.removeView(view)
            }
        }

        savedViewGroup.addView(view)
    }
}

class UserFeedbackPrompter(private val activity: Activity, private val viewGroup: ViewGroup) : UserPrompter {
    companion object {
        const val prefString = "usage"
    }
    private val prefs = activity.getSharedPreferences(prefString, Context.MODE_PRIVATE)

    private val numberOfTimesOpenedKey = "numberOfTimesOpened"
    private val userGaveFeedbackKey = "userGaveFeedback"
    private val dateTimeFirstOpenKey = "dateTimeFirstOpen"
    private val millisecondsInThreeDays = 259200000L
    private val minimumNumberOfOpensBeforeReviewRequest = 15

    private val doNothing = {
    }
    override val savedActivity: Activity
        get() = activity
    override val savedViewGroup: ViewGroup
        get() = viewGroup
    override val altInitialPosFlow: Boolean
        get() = false
    override val initialPositiveBtnAction: () -> Unit
        get() = doNothing

    override val initialPrompt: Int
        get() = R.string.review_is_user_enjoying
    override val initialPosBtnText: Int
        get() = R.string.button_yes
    override val initialNegBtnText: Int
        get() = R.string.button_negative

    override val primaryRequest: Int
        get() = R.string.review_ask_for_rating
    override val primaryPosBtnText: Int
        get() = R.string.button_positive
    override val primaryNegBtnText: Int
        get() = R.string.button_refuse

    override val secondaryRequest: Int
        get() = R.string.review_ask_for_feedback
    override val secondaryPosBtnText: Int
        get() = R.string.button_positive
    override val secondaryNegBtnText: Int
        get() = R.string.button_negative

    override val primaryPositiveBtnAction: () -> Unit
        get() = sendReviewIntent
    override val secondaryPositiveBtnAction: () -> Unit
        get() = sendGithubIntent

    override val finishedAction: () -> Unit
        get() = userHasGivenFeedback

    private val sendReviewIntent = {
        val userlandPlayStoreURI = "https://play.google.com/store/apps/details?id=tech.ula"
        val intent = Intent("android.intent.action.VIEW", Uri.parse(userlandPlayStoreURI))
        activity.startActivity(intent)
    }

    private val sendGithubIntent = {
        val githubURI = "https://github.com/CypherpunkArmory/UserLAnd"
        val intent = Intent("android.intent.action.VIEW", Uri.parse(githubURI))
        activity.startActivity(intent)
    }

    private val userHasGivenFeedback = {
        with(prefs.edit()) {
            putBoolean(userGaveFeedbackKey, true)
            apply()
        }
    }

    override fun viewShouldBeShown(): Boolean {
        return askingForFeedbackIsAppropriate()
    }

    private fun askingForFeedbackIsAppropriate(): Boolean {
        return getIsSufficientTimeElapsedSinceFirstOpen() &&
                numberOfTimesOpenedIsGreaterThanThreshold() &&
                !getUserGaveFeedback()
    }

    private fun getIsSufficientTimeElapsedSinceFirstOpen(): Boolean {
        val dateTimeFirstOpened = prefs.getLong(dateTimeFirstOpenKey, 0L)
        val dateTimeWithSufficientTimeElapsed = dateTimeFirstOpened + millisecondsInThreeDays

        return (System.currentTimeMillis() > dateTimeWithSufficientTimeElapsed)
    }

    private fun numberOfTimesOpenedIsGreaterThanThreshold(): Boolean {
        val numberTimesOpened = prefs.getInt(numberOfTimesOpenedKey, 0) + 1
        setNumberOfTimesOpened(numberTimesOpened)
        return numberTimesOpened > minimumNumberOfOpensBeforeReviewRequest
    }

    private fun setNumberOfTimesOpened(numberTimesOpened: Int) {
        with(prefs.edit()) {
            if (numberTimesOpened == 1) putLong(dateTimeFirstOpenKey, System.currentTimeMillis())
            putInt(numberOfTimesOpenedKey, numberTimesOpened)
            apply()
        }
    }

    private fun getUserGaveFeedback(): Boolean {
        return prefs.getBoolean(userGaveFeedbackKey, false)
    }
}

class CollectionOptInPrompter(private val activity: Activity, private val viewGroup: ViewGroup) : UserPrompter {
    private val prefs = activity.defaultSharedPreferences
    private val logger = SentryLogger()

    private val doNothing = {
    }
    override val savedActivity: Activity
        get() = activity
    override val savedViewGroup: ViewGroup
        get() = viewGroup
    override val altInitialPosFlow: Boolean
        get() = false
    override val initialPositiveBtnAction: () -> Unit
        get() = doNothing

    companion object {
        const val userHasOptedInPreference = "pref_opt_in"

        const val userHasBeenPromptedToOptIn = "opt_in_checked"
    }

    override val initialPrompt: Int
        get() = R.string.opt_in_help_prompt
    override val initialPosBtnText: Int
        get() = R.string.button_yes
    override val initialNegBtnText: Int
        get() = R.string.button_negative

    override val primaryRequest: Int
        get() = R.string.opt_in_error_collection_prompt
    override val primaryPosBtnText: Int
        get() = R.string.button_yes
    override val primaryNegBtnText: Int
        get() = R.string.button_negative

    override val secondaryRequest: Int
        get() = R.string.opt_in_secondary_prompt
    override val secondaryPosBtnText: Int
        get() = R.string.button_positive
    override val secondaryNegBtnText: Int
        get() = R.string.button_refuse

    override val primaryPositiveBtnAction: () -> Unit
        get() = setOptInOn
    override val secondaryPositiveBtnAction: () -> Unit
        get() = setOptInOn

    override val finishedAction: () -> Unit
        get() = userHasBeenPrompted

    override fun viewShouldBeShown(): Boolean {
        return !prefs.getBoolean(userHasBeenPromptedToOptIn, false)
    }

    fun userHasOptedIn(): Boolean {
        return prefs.getBoolean(userHasOptedInPreference, false)
    }

    private val setOptInOn = {
        logger.initialize(activity)
        with(prefs.edit()) {
            putBoolean(userHasOptedInPreference, true)
            apply()
        }
    }

    private val userHasBeenPrompted = {
        with(prefs.edit()) {
            putBoolean(userHasBeenPromptedToOptIn, true)
            apply()
        }
    }
}
