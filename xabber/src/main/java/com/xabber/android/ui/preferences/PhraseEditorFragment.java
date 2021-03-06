package com.xabber.android.ui.preferences;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.xabber.android.R;
import com.xabber.android.data.message.phrase.Phrase;
import com.xabber.android.data.message.phrase.PhraseManager;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

public class PhraseEditorFragment extends BaseSettingsFragment {

    private OnPhraseEditorFragmentInteractionListener mListener;

    @Override
    protected void onInflate(Bundle savedInstanceState) {
        addPreferencesFromResource(R.xml.phrase_editor);
    }

    @Override
    public void onResume() {
        super.onResume();
        Phrase phrase = mListener.getPhrase();
        Preference notificationPref = findPreference("notification_settings");
        if (phrase != null) {
            notificationPref.setIntent(CustomNotifySettings.createIntent(getActivity(), phrase.getId()));
            notificationPref.setEnabled(true);
        } else {
            notificationPref.setEnabled(false);
            notificationPref.setSummary(R.string.events_use_custom_not_allowed_summary);
        }
    }

    @Override
    protected Map<String, Object> getValues() {
        Phrase phrase = mListener.getPhrase();
        Map<String, Object> source = new HashMap<>();

        putValue(source, R.string.phrase_text_key,
                phrase == null ? "" : phrase.getText());
        putValue(source, R.string.phrase_user_key,
                phrase == null ? "" : phrase.getUser());
        putValue(source, R.string.phrase_group_key, phrase == null ? ""
                : phrase.getGroup());
        putValue(source, R.string.phrase_regexp_key, phrase != null && phrase.isRegexp());
        return source;
    }

    @Override
    protected boolean setValues(Map<String, Object> source,
                                Map<String, Object> result) {


        String text = getString(result, R.string.phrase_text_key);
        String user = getString(result, R.string.phrase_user_key);
        String group = getString(result, R.string.phrase_group_key);
        boolean regexp = getBoolean(result, R.string.phrase_regexp_key);

        Log.i("PhraseEditorFragment", "setValues. text: " + text);

        if (regexp) {
            try {
                Phrase.compile(text);
                Phrase.compile(user);
                Phrase.compile(group);
            } catch (PatternSyntaxException e) {
                Toast.makeText(getActivity(), e.getMessage(), Toast.LENGTH_LONG).show();
                return false;
            }
        }

        Phrase phrase = mListener.getPhrase();

        if (phrase == null && "".equals(text) && "".equals(user) && "".equals(group)) {
            Toast.makeText(getActivity(), R.string.events_phrases_error, Toast.LENGTH_LONG).show();
            return false;
        }
        Log.i("PhraseEditorFragment", "updateOrCreatePhrase");
        PhraseManager.getInstance().updateOrCreatePhrase(phrase, text, user, group, regexp, null);

        mListener.setPhrase(phrase);

        return true;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mListener = (OnPhraseEditorFragmentInteractionListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement OnThemeSettingsFragmentInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    public interface OnPhraseEditorFragmentInteractionListener {
        Phrase getPhrase();

        void setPhrase(Phrase phrase);
    }
}
