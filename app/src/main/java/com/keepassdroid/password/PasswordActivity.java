/*
 * Copyright 2017 Brian Pellin, Jeremy Jamet / Kunzisoft.
 *     
 * This file is part of KeePass DX.
 *
 *  KeePass DX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  KeePass DX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePass DX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.keepassdroid.password;

import android.Manifest;
import android.app.Activity;
import android.app.assist.AssistStructure;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.hardware.fingerprint.FingerprintManagerCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.keepassdroid.activities.GroupActivity;
import com.keepassdroid.activities.LockingActivity;
import com.keepassdroid.app.App;
import com.keepassdroid.autofill.AutofillHelper;
import com.keepassdroid.compat.BackupManagerCompat;
import com.keepassdroid.compat.ClipDataCompat;
import com.keepassdroid.compat.EditorCompat;
import com.keepassdroid.database.Database;
import com.keepassdroid.database.edit.LoadDB;
import com.keepassdroid.database.edit.OnFinish;
import com.keepassdroid.dialog.PasswordEncodingDialogHelper;
import com.keepassdroid.fingerprint.FingerPrintAnimatedVector;
import com.keepassdroid.fingerprint.FingerPrintHelper;
import com.keepassdroid.settings.PreferencesUtil;
import com.keepassdroid.tasks.ProgressTask;
import com.keepassdroid.utils.EmptyUtils;
import com.keepassdroid.utils.MenuUtil;
import com.keepassdroid.utils.UriUtil;
import com.keepassdroid.view.FingerPrintDialog;
import com.keepassdroid.view.KeyFileHelper;
import com.kunzisoft.keepass.R;

import java.io.File;
import java.io.FileNotFoundException;

import permissions.dispatcher.NeedsPermission;
import permissions.dispatcher.OnNeverAskAgain;
import permissions.dispatcher.OnPermissionDenied;
import permissions.dispatcher.OnShowRationale;
import permissions.dispatcher.PermissionRequest;
import permissions.dispatcher.RuntimePermissions;

import static com.keepassdroid.fingerprint.FingerPrintHelper.Mode.NOT_CONFIGURED_MODE;
import static com.keepassdroid.fingerprint.FingerPrintHelper.Mode.OPEN_MODE;
import static com.keepassdroid.fingerprint.FingerPrintHelper.Mode.STORE_MODE;

@RuntimePermissions
public class PasswordActivity extends LockingActivity
        implements FingerPrintHelper.FingerPrintCallback, UriIntentInitTaskCallback {

    public static final String KEY_DEFAULT_FILENAME = "defaultFileName";
    public static final int RESULT_EXIT_LOCK = 1450;

    private static final String KEY_PASSWORD = "password";
    private static final String KEY_LAUNCH_IMMEDIATELY = "launchImmediately";

    private Uri mDbUri = null;
    SharedPreferences prefs;
    SharedPreferences prefsNoBackup;

    private FingerPrintHelper fingerPrintHelper;
    private boolean fingerprintMustBeConfigured = true;
    private boolean mRememberKeyfile;

    private FingerPrintHelper.Mode fingerPrintMode;
    private static final String PREF_KEY_VALUE_PREFIX = "valueFor_"; // key is a combination of db file name and this prefix
    private static final String PREF_KEY_IV_PREFIX = "ivFor_"; // key is a combination of db file name and this prefix

    private View fingerprintContainerView;
    private FingerPrintAnimatedVector fingerPrintAnimatedVector;
    private TextView fingerprintTextView;
    private TextView filenameView;
    private EditText passwordView;
    private EditText keyFileView;
    private Button confirmButtonView;
    private CompoundButton checkboxPasswordView;
    private CompoundButton checkboxKeyfileView;
    private CompoundButton checkboxDefaultDatabaseView;

    private DefaultCheckChange defaultCheckChange;
    private ValidateButtonViewClickListener validateButtonViewClickListener;

    private KeyFileHelper keyFileHelper;

    private AutofillHelper autofillHelper;

    public static void launch(
            Activity act,
            String fileName) throws FileNotFoundException {
        launch(act, fileName, "");
    }

    public static void launch(
            Activity act,
            String fileName,
            String keyFile) throws FileNotFoundException {
        verifyFileNameUriFromLaunch(fileName);

        Intent intent = new Intent(act, PasswordActivity.class);
        intent.putExtra(UriIntentInitTask.KEY_FILENAME, fileName);
        intent.putExtra(UriIntentInitTask.KEY_KEYFILE, keyFile);
        // only to avoid visible  flickering when redirecting
        act.startActivityForResult(intent, 0);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void launch(
            Activity act,
            String fileName,
            AssistStructure assistStructure) throws FileNotFoundException {
        launch(act, fileName, "", assistStructure);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void launch(
            Activity act,
            String fileName,
            String keyFile,
            AssistStructure assistStructure) throws FileNotFoundException {
        verifyFileNameUriFromLaunch(fileName);

        if ( assistStructure != null ) {
            Intent intent = new Intent(act, PasswordActivity.class);
            intent.putExtra(UriIntentInitTask.KEY_FILENAME, fileName);
            intent.putExtra(UriIntentInitTask.KEY_KEYFILE, keyFile);
            AutofillHelper.addAssistStructureExtraInIntent(intent, assistStructure);
            act.startActivityForResult(intent, AutofillHelper.AUTOFILL_RESPONSE_REQUEST_CODE);
        } else {
            launch(act, fileName, keyFile);
        }
    }

    private static void verifyFileNameUriFromLaunch(String fileName) throws FileNotFoundException {
        if (EmptyUtils.isNullOrEmpty(fileName)) {
            throw new FileNotFoundException();
        }

        Uri uri = UriUtil.parseDefaultFile(fileName);
        assert uri != null;
        String scheme = uri.getScheme();

        if (!EmptyUtils.isNullOrEmpty(scheme) && scheme.equalsIgnoreCase("file")) {
            File dbFile = new File(uri.getPath());
            if (!dbFile.exists()) {
                throw new FileNotFoundException();
            }
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AutofillHelper.onActivityResultSetResultAndFinish(this, requestCode, resultCode, data);
        }
        // TODO Tests

        boolean keyFileResult = false;
        if (keyFileHelper != null) {
            keyFileResult = keyFileHelper.onActivityResultCallback(requestCode, resultCode, data,
                    new KeyFileHelper.KeyFileCallback() {
                        @Override
                        public void onKeyFileResultCallback(Uri uri) {
                            if (uri != null) {
                                populateKeyFileTextView(uri.toString());
                            }
                        }
                    });
        }
        if (!keyFileResult) {
            // this block if not a key file response
            switch (resultCode) {
                case RESULT_EXIT_LOCK:
                case Activity.RESULT_CANCELED:
                    setEmptyViews();
                    App.getDB().clear();
                    break;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefsNoBackup = PreferencesUtil.getNoBackupSharedPreferences(getApplicationContext());

        mRememberKeyfile = prefs.getBoolean(getString(R.string.keyfile_key),
                getResources().getBoolean(R.bool.keyfile_default));

        setContentView(R.layout.password);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.app_name));
        setSupportActionBar(toolbar);
        assert getSupportActionBar() != null;
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);

        confirmButtonView = (Button) findViewById(R.id.pass_ok);
        filenameView = (TextView) findViewById(R.id.filename);
        passwordView = (EditText) findViewById(R.id.password);
        keyFileView = (EditText) findViewById(R.id.pass_keyfile);
        checkboxPasswordView = (CompoundButton) findViewById(R.id.password_checkbox);
        checkboxKeyfileView = (CompoundButton) findViewById(R.id.keyfile_checkox);
        checkboxDefaultDatabaseView = (CompoundButton) findViewById(R.id.default_database);

        View browseView = findViewById(R.id.browse_button);
        keyFileHelper = new KeyFileHelper(PasswordActivity.this);
        browseView.setOnClickListener(keyFileHelper.getOpenFileOnClickViewListener());

        passwordView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void afterTextChanged(Editable editable) {
                if (!editable.toString().isEmpty() && !checkboxKeyfileView.isChecked())
                    checkboxPasswordView.setChecked(true);
            }
        });
        keyFileView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void afterTextChanged(Editable editable) {
                if (!editable.toString().isEmpty() && !checkboxKeyfileView.isChecked())
                    checkboxKeyfileView.setChecked(true);
            }
        });

        defaultCheckChange = new DefaultCheckChange();
        validateButtonViewClickListener = new ValidateButtonViewClickListener();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fingerprintContainerView = findViewById(R.id.fingerprint_container);
            fingerprintTextView = (TextView) findViewById(R.id.fingerprint_label);
            initForFingerprint();
            fingerPrintAnimatedVector = new FingerPrintAnimatedVector(this,
                            (ImageView) findViewById(R.id.fingerprint_image));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            autofillHelper = new AutofillHelper();
            autofillHelper.retrieveAssistStructure(getIntent());
        }
    }

    @Override
    protected void onResume() {
        // If the application was shutdown make sure to clear the password field, if it
        // was saved in the instance state
        if (App.isShutdown()) {
            setEmptyViews();
        }

        // Clear the shutdown flag
        App.clearShutdown();

        // For check shutdown
        super.onResume();

        new UriIntentInitTask(this, mRememberKeyfile)
                .execute(getIntent());
    }

    @Override
    public void onPostInitTask(Uri dbUri, Uri keyFileUri, Integer errorStringId) {
        mDbUri = dbUri;

        if (errorStringId != null) {
            Toast.makeText(PasswordActivity.this, errorStringId, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Verify permission to read file
        if (mDbUri != null
                && !dbUri.getScheme().contains("content"))
            PasswordActivityPermissionsDispatcher
                    .doNothingWithPermissionCheck(this);

        // Define title
        String dbUriString = (mDbUri == null) ? "" : mDbUri.toString();
        if (!dbUriString.isEmpty()) {
            if (PreferencesUtil.isFullFilePathEnable(this))
                filenameView.setText(dbUriString);
            else
                filenameView.setText(new File(mDbUri.getPath()).getName()); // TODO Encapsulate
        }

        // Define Key File text
        String keyUriString = (keyFileUri == null) ? "" : keyFileUri.toString();
        if (!keyUriString.isEmpty() && mRememberKeyfile) { // Bug KeepassDX #18
            populateKeyFileTextView(keyUriString);
        }

        // Define listeners for default database checkbox and validate button
        checkboxDefaultDatabaseView.setOnCheckedChangeListener(defaultCheckChange);
        confirmButtonView.setOnClickListener(validateButtonViewClickListener);

        // Retrieve settings for default database
        String defaultFilename = prefs.getString(KEY_DEFAULT_FILENAME, "");
        if (mDbUri!=null
                && !EmptyUtils.isNullOrEmpty(mDbUri.getPath())
                && UriUtil.equalsDefaultfile(mDbUri, defaultFilename)) {
            checkboxDefaultDatabaseView.setChecked(true);
        }

        // checks if fingerprint is available, will also start listening for fingerprints when available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkFingerprintAvailability();
            if (fingerPrintAnimatedVector != null) {
                fingerPrintAnimatedVector.startScan();
            }
        }

        // If Activity is launch with a password and want to open directly
        Intent intent = getIntent();
        String password = intent.getStringExtra(KEY_PASSWORD);
        boolean launch_immediately = intent.getBooleanExtra(KEY_LAUNCH_IMMEDIATELY, false);
        if (password != null) {
            populatePasswordTextView(password);
        }
        if (launch_immediately) {
            verifyCheckboxesAndLoadDatabase(password, keyFileUri);
        }
    }

    private void setEmptyViews() {
        populatePasswordTextView(null);
        // Bug KeepassDX #18
        if (!mRememberKeyfile) {
            populateKeyFileTextView(null);
        }
    }

    private void populatePasswordTextView(String text) {
        if (text == null || text.isEmpty()) {
            passwordView.setText("");
            if (checkboxPasswordView.isChecked())
                checkboxPasswordView.setChecked(false);
        } else {
            passwordView.setText(text);
            if (!checkboxPasswordView.isChecked())
                checkboxPasswordView.setChecked(true);
        }
    }

    private void populateKeyFileTextView(String text) {
        if (text == null || text.isEmpty()) {
            keyFileView.setText("");
            if (checkboxKeyfileView.isChecked())
                checkboxKeyfileView.setChecked(false);
        } else {
            keyFileView.setText(text);
            if (!checkboxKeyfileView.isChecked())
                checkboxKeyfileView.setChecked(true);
        }
    }

    // fingerprint related code here
    @RequiresApi(api = Build.VERSION_CODES.M)
    private void initForFingerprint() {
        fingerPrintMode = NOT_CONFIGURED_MODE;

        fingerPrintHelper = new FingerPrintHelper(this, this);

        // when text entered we can enable the logon/purchase button and if required update encryption/decryption mode
        passwordView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                    final CharSequence s,
                    final int start,
                    final int count,
                    final int after) {}

            @Override
            public void onTextChanged(
                    final CharSequence s,
                    final int start,
                    final int before,
                    final int count) {}

            @Override
            public void afterTextChanged(final Editable s) {
                if ( !fingerprintMustBeConfigured ) {
                    final boolean validInput = s.length() > 0;
                    // encrypt or decrypt mode based on how much input or not
                    setFingerPrintTextView(validInput ? R.string.store_with_fingerprint : R.string.scanning_fingerprint);
                    if (validInput)
                        toggleFingerprintMode(STORE_MODE);
                    else
                        toggleFingerprintMode(OPEN_MODE);
                }
            }
        });

        // callback for fingerprint findings
        fingerPrintHelper.setAuthenticationCallback(new FingerprintManagerCompat.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(
                    final int errorCode,
                    final CharSequence errString) {
                Log.i(getClass().getName(), errString.toString());
            }

            @Override
            public void onAuthenticationHelp(
                    final int helpCode,
                    final CharSequence helpString) {
                showError(helpString);
                reInitWithSameFingerprintMode();
                fingerprintTextView.setText(helpString);
            }

            @Override
            public void onAuthenticationFailed() {
                showError(R.string.fingerprint_not_recognized);
                reInitWithSameFingerprintMode();
            }

            @Override
            public void onAuthenticationSucceeded(final FingerprintManagerCompat.AuthenticationResult result) {
                switch (fingerPrintMode) {
                    case STORE_MODE:
                        // newly store the entered password in encrypted way
                        final String password = passwordView.getText().toString();
                        fingerPrintHelper.encryptData(password);
                        break;
                    case OPEN_MODE:
                        // retrieve the encrypted value from preferences
                        final String encryptedValue = prefsNoBackup.getString(getPreferenceKeyValue(), null);
                        if (encryptedValue != null) {
                            fingerPrintHelper.decryptData(encryptedValue);
                        }
                        break;
                }
            }
        });
    }

    private String getPreferenceKeyValue() {
        // makes it possible to store passwords uniqly per database
        return PREF_KEY_VALUE_PREFIX + (mDbUri != null ? mDbUri.getPath() : "");
    }

    private String getPreferenceKeyIvSpec() {
        return PREF_KEY_IV_PREFIX + (mDbUri != null ? mDbUri.getPath() : "");
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void initEncryptData() {
        setFingerPrintTextView(R.string.store_with_fingerprint);
        fingerPrintMode = STORE_MODE;
        if (fingerPrintHelper != null)
            fingerPrintHelper.initEncryptData();
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void initDecryptData() {
        setFingerPrintTextView(R.string.scanning_fingerprint);
        fingerPrintMode = OPEN_MODE;
        if (fingerPrintHelper != null) {
            final String ivSpecValue = prefsNoBackup.getString(getPreferenceKeyIvSpec(), null);
            if (ivSpecValue != null)
                fingerPrintHelper.initDecryptData(ivSpecValue);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private synchronized void toggleFingerprintMode(final FingerPrintHelper.Mode newMode) {
        if( !newMode.equals(fingerPrintMode) ) {
            fingerPrintMode = newMode;
            reInitWithSameFingerprintMode();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private synchronized void reInitWithSameFingerprintMode() {
        switch (fingerPrintMode) {
            case STORE_MODE:
                initEncryptData();
                break;
            case OPEN_MODE:
                initDecryptData();
                break;
        }
        // Show fingerprint key deletion
        invalidateOptionsMenu();
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (fingerPrintAnimatedVector != null) {
                fingerPrintAnimatedVector.stopScan();
            }
            // stop listening when we go in background
            fingerPrintMode = NOT_CONFIGURED_MODE;
            if (fingerPrintHelper != null) {
                fingerPrintHelper.stopListening();
            }
        }
    }

    private void setFingerPrintVisibility(final int vis) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                fingerprintContainerView.setVisibility(vis);
            }
        });
    }

    private void setFingerPrintTextView(final int textId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                fingerprintTextView.setText(textId);
            }
        });
    }

    private void setFingerPrintAlphaImageView(final float alpha) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                fingerprintContainerView.setAlpha(alpha);
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private synchronized void checkFingerprintAvailability() {
        // fingerprint not supported (by API level or hardware) so keep option hidden
        // or manually disable
        if (!PreferencesUtil .isFingerprintEnable(getApplicationContext())
                || !FingerPrintHelper.isFingerprintSupported(FingerprintManagerCompat.from(this))) {
            setFingerPrintVisibility(View.GONE);
        }
        // fingerprint is available but not configured show icon but in disabled state with some information
        else {
            // show explanations
            fingerprintContainerView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    FingerPrintDialog fingerPrintDialog = new FingerPrintDialog();
                    fingerPrintDialog.show(getSupportFragmentManager(), "fingerprintDialog");
                }
            });
            setFingerPrintVisibility(View.VISIBLE);

            if (!fingerPrintHelper.hasEnrolledFingerprints()) {
                setFingerPrintAlphaImageView(0.6f);
                // This happens when no fingerprints are registered. Listening won't start
                setFingerPrintTextView(R.string.configure_fingerprint);
            }
            // finally fingerprint available and configured so we can use it
            else {
                fingerprintMustBeConfigured = false;
                setFingerPrintAlphaImageView(1f);

                // fingerprint available but no stored password found yet for this DB so show info don't listen
                if (!prefsNoBackup.contains(getPreferenceKeyValue())) {
                    setFingerPrintTextView(R.string.no_password_stored);
                    // listen for encryption
                    initEncryptData();
                }
                // all is set here so we can confirm to user and start listening for fingerprints
                else {
                    // listen for decryption
                    initDecryptData();
                }
            }
        }

        // Show fingerprint key deletion
        invalidateOptionsMenu();
    }

    private void removePrefsNoBackupKeys() {
        prefsNoBackup.edit()
                .remove(getPreferenceKeyValue())
                .remove(getPreferenceKeyIvSpec())
                .apply();
    }

    @Override
    public void handleEncryptedResult(
            final String value,
            final String ivSpec) {
        prefsNoBackup.edit()
                .putString(getPreferenceKeyValue(), value)
                .putString(getPreferenceKeyIvSpec(), ivSpec)
                .apply();
        // and remove visual input to reset UI
        confirmButtonView.performClick(); // TODO remove click
        setFingerPrintTextView(R.string.encrypted_value_stored);
    }

    @Override
    public void handleDecryptedResult(final String passwordValue) {
        // Load database directly
        String key = keyFileView.getText().toString();
        loadDatabase(passwordValue, key);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onInvalidKeyException(Exception e) {
        showError(R.string.fingerprint_invalid_key);
        removePrefsNoBackupKeys();
        e.printStackTrace();
        reInitWithSameFingerprintMode(); // restarts listening
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onFingerPrintException(Exception e) {
        showError(R.string.fingerprint_error);
        e.printStackTrace();
        reInitWithSameFingerprintMode();
    }

    private void showError(final int messageId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), messageId, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showError(final CharSequence message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private class DefaultCheckChange implements CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(
                CompoundButton buttonView,
                boolean isChecked) {

            String newDefaultFileName = "";
            if (isChecked) {
                newDefaultFileName = mDbUri.toString();
            }

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_DEFAULT_FILENAME, newDefaultFileName);
            EditorCompat.apply(editor);

            BackupManagerCompat backupManager = new BackupManagerCompat(PasswordActivity.this);
            backupManager.dataChanged();
        }
    }

    private class ValidateButtonViewClickListener implements View.OnClickListener {
        public void onClick(View view) {
            String pass = passwordView.getText().toString();
            String key = keyFileView.getText().toString();
            verifyCheckboxesAndLoadDatabase(pass, key);
        }
    }

    private void verifyCheckboxesAndLoadDatabase(
            String pass,
            String keyfile) {
        verifyCheckboxesAndLoadDatabase(pass, UriUtil.parseDefaultFile(keyfile));
    }

    private void verifyCheckboxesAndLoadDatabase(
            String pass,
            Uri keyfile) {
        if (!checkboxPasswordView.isChecked()) {
            pass = "";
        }
        if (!checkboxKeyfileView.isChecked()) {
            keyfile = null;
        }
        loadDatabase(pass, keyfile);
    }

    private void loadDatabase(
            String pass,
            String keyfile) {
        loadDatabase(pass, UriUtil.parseDefaultFile(keyfile));
    }

    private void loadDatabase(
            String pass,
            Uri keyfile) {

        // Clear before we load
        Database db = App.getDB();
        db.clear();

        // Clear the shutdown flag
        App.clearShutdown();

        Handler handler = new Handler();
        AfterLoad afterLoad = new AfterLoad(handler, db);

        LoadDB task = new LoadDB(db, PasswordActivity.this, mDbUri, pass, keyfile, afterLoad);
        ProgressTask pt = new ProgressTask(PasswordActivity.this, task, R.string.loading_database);
        pt.run();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        MenuUtil.defaultMenuInflater(inflater, menu);
        if (!fingerprintMustBeConfigured
                && prefsNoBackup.contains(getPreferenceKeyValue()) )
            inflater.inflate(R.menu.fingerprint, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                break;
            case R.id.menu_fingerprint_remove_key:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    fingerPrintHelper.deleteEntryKey();
                    removePrefsNoBackupKeys();
                    fingerPrintMode = NOT_CONFIGURED_MODE;
                    checkFingerprintAvailability();
                }
                break;
            default:
                return MenuUtil.onDefaultMenuOptionsItemSelected(this, item);
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // NOTE: delegate the permission handling to generated method
        PasswordActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults);
    }

    /**
     * Called after verify and try to opening the database
     */
    private final class AfterLoad extends OnFinish {

        protected Database db;

        AfterLoad(
                Handler handler,
                Database db) {
            super(handler);

            this.db = db;
        }

        @Override
        public void run() {

            // Recheck fingerprint if error
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                //setEmptyViews();
                //mMessage = getString(R.string.fingerprint_error) + " : " + mMessage;
                // TODO Change fingerprint message
                // Stay with the same mode
                reInitWithSameFingerprintMode();
            }

            if (db.passwordEncodingError) {
                PasswordEncodingDialogHelper dialog = new PasswordEncodingDialogHelper();
                dialog.show(PasswordActivity.this, new OnClickListener() {

                    @Override
                    public void onClick(
                            DialogInterface dialog,
                            int which) {
                        launchGroupActivity();
                    }

                });
            } else if (mSuccess) {
                launchGroupActivity();
            } else {
                displayMessage(PasswordActivity.this);
            }
        }
    }

    private void launchGroupActivity() {
        AssistStructure assistStructure = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            assistStructure = autofillHelper.getAssistStructure();
            if (assistStructure != null) {
                GroupActivity.launch(PasswordActivity.this, assistStructure);
            }
        }
        if (assistStructure == null) {
            GroupActivity.launch(PasswordActivity.this);
        }
    }

    private static class UriIntentInitTask extends AsyncTask<Intent, Void, Integer> {

        static final String KEY_FILENAME = "fileName";
        static final String KEY_KEYFILE = "keyFile";
        private static final String VIEW_INTENT = "android.intent.action.VIEW";

        private UriIntentInitTaskCallback uriIntentInitTaskCallback;
        private boolean isKeyFileNeeded;
        private Uri databaseUri;
        private Uri keyFileUri;

        UriIntentInitTask(UriIntentInitTaskCallback uriIntentInitTaskCallback, boolean isKeyFileNeeded) {
            this.uriIntentInitTaskCallback = uriIntentInitTaskCallback;
            this.isKeyFileNeeded = isKeyFileNeeded;
        }

        @Override
        protected Integer doInBackground(Intent... args) {
            Intent intent = args[0];
            String action = intent.getAction();
            if (action != null && action.equals(VIEW_INTENT)) {
                Uri incoming = intent.getData();
                databaseUri = incoming;
                keyFileUri = ClipDataCompat.getUriFromIntent(intent, KEY_KEYFILE);

                if (incoming == null) {
                    return R.string.error_can_not_handle_uri;
                } else if (incoming.getScheme().equals("file")) {
                    String fileName = incoming.getPath();

                    if (fileName.length() == 0) {
                        // No file name
                        return R.string.file_not_found;
                    }

                    File dbFile = new File(fileName);
                    if (!dbFile.exists()) {
                        // File does not exist
                        return R.string.file_not_found;
                    }

                    if (keyFileUri == null) {
                        keyFileUri = getKeyFile(databaseUri);
                    }
                } else if (incoming.getScheme().equals("content")) {
                    if (keyFileUri == null) {
                        keyFileUri = getKeyFile(databaseUri);
                    }
                } else {
                    return R.string.error_can_not_handle_uri;
                }

            } else {
                databaseUri = UriUtil.parseDefaultFile(intent.getStringExtra(KEY_FILENAME));
                keyFileUri = UriUtil.parseDefaultFile(intent.getStringExtra(KEY_KEYFILE));

                if (keyFileUri == null || keyFileUri.toString().length() == 0) {
                    keyFileUri = getKeyFile(databaseUri);
                }
            }
            return null;
        }

        public void onPostExecute(Integer result) {
            uriIntentInitTaskCallback.onPostInitTask(databaseUri, keyFileUri, result);
        }

        private Uri getKeyFile(Uri dbUri) {
            if (isKeyFileNeeded) {
                return App.getFileHistory().getFileByName(dbUri);
            } else {
                return null;
            }
        }
    }

    @NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    public void doNothing() {}

    @OnShowRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    void showRationaleForExternalStorage(final PermissionRequest request) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.permission_external_storage_rationale_read_database)
                .setPositiveButton(R.string.allow, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        request.proceed();
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        request.cancel();
                    }
                })
                .show();
    }

    @OnPermissionDenied(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    void showDeniedForExternalStorage() {
        Toast.makeText(this, R.string.permission_external_storage_denied, Toast.LENGTH_SHORT).show();
        finish();
    }

    @OnNeverAskAgain(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    void showNeverAskForExternalStorage() {
        Toast.makeText(this, R.string.permission_external_storage_never_ask, Toast.LENGTH_SHORT).show();
        finish();
    }
}