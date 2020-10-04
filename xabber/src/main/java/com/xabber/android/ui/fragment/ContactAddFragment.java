package com.xabber.android.ui.fragment;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import com.xabber.android.R;
import com.xabber.android.data.Application;
import com.xabber.android.data.NetworkException;
import com.xabber.android.data.account.AccountManager;
import com.xabber.android.data.entity.AccountJid;
import com.xabber.android.data.entity.UserJid;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.data.roster.PresenceManager;
import com.xabber.android.data.roster.RosterManager;
import com.xabber.android.ui.adapter.AccountChooseAdapter;
import com.xabber.android.ui.helper.ContactAdder;

import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.stringprep.XmppStringprepException;

import java.util.ArrayList;
import java.util.Collection;

public class ContactAddFragment extends GroupEditorFragment
        implements AdapterView.OnItemSelectedListener, ContactAdder {

    private static final String SAVED_NAME = "com.xabber.android.ui.fragment..ContactAddFragment.SAVED_NAME";
    private static final String SAVED_ACCOUNT = "com.xabber.android.ui.fragment..ContactAddFragment.SAVED_ACCOUNT";
    private static final String SAVED_USER = "com.xabber.android.ui.fragment..ContactAddFragment.SAVED_USER";
    Listener listenerActivity;
    private Spinner accountView;
    private EditText userView;
    private EditText nameView;
    private String name;

    public static ContactAddFragment newInstance(AccountJid account, UserJid user) {
        ContactAddFragment fragment = new ContactAddFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_ACCOUNT, account);
        args.putParcelable(ARG_USER, user);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        listenerActivity = (Listener)activity;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_contact_add, container, false);

        if (savedInstanceState != null) {
            name = savedInstanceState.getString(SAVED_NAME);
            setAccount((AccountJid) savedInstanceState.getParcelable(SAVED_ACCOUNT));
            setUser((UserJid) savedInstanceState.getParcelable(SAVED_USER));
        } else {
            if (getAccount() == null || getUser() == null) {
                name = null;
            } else {
                name = RosterManager.getInstance().getName(getAccount(), getUser());
                if (getUser().getJid().asBareJid().toString().equals(name)) {
                    name = null;
                }
            }
        }
        if (getAccount() == null) {
            Collection<AccountJid> accounts = AccountManager.getInstance().getEnabledAccounts();
            if (accounts.size() == 1) {
                setAccount(accounts.iterator().next());
            }
        }

        setUpAccountView((Spinner) view.findViewById(R.id.contact_account));

        userView = (EditText) view.findViewById(R.id.contact_user);
        nameView = (EditText) view.findViewById(R.id.contact_name);

        if (getUser() != null) {
            userView.setText(getUser().toString());
        }
        if (name != null) {
            nameView.setText(name);
        }

        return view;
    }

    private void setUpAccountView(Spinner view) {
        accountView = view;
        accountView.setAdapter(new AccountChooseAdapter(getActivity()));
        accountView.setOnItemSelectedListener(this);

        AccountJid account = getAccount();

        if (account != null) {
            for (int position = 0; position < accountView.getCount(); position++) {
                AccountJid itemAtPosition = (AccountJid) accountView.getItemAtPosition(position);
                LogManager.i(this, "itemAtPosition " + itemAtPosition + " account " + account);
                if (account.equals(accountView.getItemAtPosition(position))) {
                    accountView.setSelection(position);
                    break;
                }
            }
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        getListView().setVisibility(View.GONE);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(SAVED_ACCOUNT, getAccount());
        outState.putString(SAVED_USER, userView.getText().toString());
        outState.putString(SAVED_NAME, nameView.getText().toString());

    }

    @Override
    public void onDetach() {
        super.onDetach();
        listenerActivity = null;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        AccountJid selectedAccount = (AccountJid) accountView.getSelectedItem();

        if (selectedAccount == null) {
            onNothingSelected(parent);
            setAccount(null);
        } else {
            if (listenerActivity != null)
                listenerActivity.onAccountSelected(selectedAccount);

            if (!selectedAccount.equals(getAccount())) {
                setAccount(selectedAccount);
                setAccountGroups();
                updateGroups();
            }

            if (getListView().getVisibility() == View.GONE) {
                getListView().setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    @Override
    public void addContact() {
        final AccountJid account = (AccountJid) accountView.getSelectedItem();
        if (account == null || getAccount() == null) {
            Toast.makeText(getActivity(), getString(R.string.EMPTY_ACCOUNT), Toast.LENGTH_LONG).show();
            return;
        }

        String contactString = userView.getText().toString();
        contactString = contactString.trim();

        if (contactString.contains(" ")) {
            userView.setError(getString(R.string.INCORRECT_USER_NAME));
            return;
        }

        if (TextUtils.isEmpty(contactString)) {
            userView.setError(getString(R.string.EMPTY_USER_NAME));
            return ;
        }

        final UserJid user;
        try {
            EntityBareJid entityFullJid = JidCreate.entityBareFrom(contactString);
            user = UserJid.from(entityFullJid);
        } catch (XmppStringprepException | UserJid.UserJidCreateException  e) {
            e.printStackTrace();
            userView.setError(getString(R.string.INCORRECT_USER_NAME));
            return;
        }

        if (listenerActivity != null)
            listenerActivity.showProgress(true);
        final String name = nameView.getText().toString();
        final ArrayList<String> groups = getSelected();

        Application.getInstance().runInBackgroundUserRequest(new Runnable() {
            @Override
            public void run() {
                try {
                    RosterManager.getInstance().createContact(account, user, name, groups);
                    PresenceManager.getInstance().requestSubscription(account, user);
                } catch (SmackException.NotLoggedInException | SmackException.NotConnectedException e) {
                    Application.getInstance().onError(R.string.NOT_CONNECTED);
                    stopAddContactProcess(false);
                } catch (XMPPException.XMPPErrorException e) {
                    Application.getInstance().onError(R.string.XMPP_EXCEPTION);
                    stopAddContactProcess(false);
                } catch (SmackException.NoResponseException e) {
                    Application.getInstance().onError(R.string.CONNECTION_FAILED);
                    stopAddContactProcess(false);
                } catch (NetworkException e) {
                    Application.getInstance().onError(e);
                    stopAddContactProcess(false);
                } catch (InterruptedException e) {
                    LogManager.exception(this, e);
                    stopAddContactProcess(false);
                }

                stopAddContactProcess(true);
            }
        });
    }

    private void stopAddContactProcess(final boolean success) {
        Application.getInstance().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (listenerActivity != null)
                    listenerActivity.showProgress(false);
                if (success) getActivity().finish();
            }
        });
    }

    public interface Listener {
        void onAccountSelected(AccountJid account);
        void showProgress(boolean show);
    }
}
