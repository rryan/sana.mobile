
package org.sana.android.activity;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.sana.R;
import org.sana.android.Constants;
import org.sana.android.app.Locales;
import org.sana.android.content.DispatchResponseReceiver;
import org.sana.android.db.BinaryDAO;
import org.sana.android.db.EncounterDAO;
import org.sana.android.db.SanaDB.BinarySQLFormat;
import org.sana.android.fragment.BaseRunnerFragment;
import org.sana.android.media.EducationResource.Audience;
import org.sana.android.procedure.PictureElement;
import org.sana.android.procedure.Procedure;
import org.sana.android.provider.Encounters;
import org.sana.android.provider.Events.EventType;
import org.sana.android.provider.Observations;
import org.sana.android.provider.Procedures;
import org.sana.android.provider.Tasks;
import org.sana.android.service.PluginService;
import org.sana.android.service.impl.DispatchService;
import org.sana.android.task.ImageProcessingTask;
import org.sana.android.task.ImageProcessingTaskRequest;
import org.sana.android.util.Logf;
import org.sana.android.util.SanaUtil;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentFilter.MalformedMimeTypeException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.LocalBroadcastManager;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.widget.Toast;


/** Base class activity for containing a BaseRunnerFragment. Additional logic is
 * built into this class to handle launching and capturing returned values from
 * Activities used to capture data along with initiating procedure saving,
 * reloading, and uploading.
 * 
 * @author Sana Development Team */
public abstract class BaseRunner extends FragmentActivity implements BaseRunnerFragment.ProcedureListener{

    public static final String TAG = BaseRunner.class.getSimpleName();
    public static final String INTENT_KEY_STRING = "intentKey";
    public static final String INTENT_EXTRAS_KEY = "extras";
    public static final String PLUGIN_INTENT_KEY = "pluginIntent";

    // Dialog IDs
    public static final int DIALOG_ALREADY_UPLOADED = 7;
    public static final int DIALOG_LOOKUP_PROGRESS = 1;
    public static final int DIALOG_LOAD_PROGRESS = 2;
    public static final int DIALOG_SIGNAL_STRENGTH = 16;
    public static final int DIALOG_UPLOAD_RESULT = 32;

    // Options
    public static final int OPTION_SAVE_EXIT = 0;
    public static final int OPTION_DISCARD_EXIT = 1;
    public static final int OPTION_VIEW_PAGES = 2;
    public static final int OPTION_HELP = 3;

    // Intent
    public static final int RUN_DISCARD = -1;
    public static final int CAMERA_INTENT_REQUEST_CODE = 1;
    public static final int BARCODE_INTENT_REQUEST_CODE = 2;
    public static final int INFO_INTENT_REQUEST_CODE = 7;
    public static final int PLUGIN_INTENT_REQUEST_CODE = 4;
    public static final int IMPLICIT_PLUGIN_INTENT_REQUEST_CODE = 8;
    public static final int OBSERVATION_RESULT_CODE = 16;
    public static final int TELEPHONY_CODE = 32;

    // State instance fields
    private static String[] params;
    private Procedure p = null;
    private Uri thisSavedProcedure;
    private Intent mEncounterState = new Intent();
    private boolean wasOnDonePage = false;
    protected ProgressDialog mUploadingDialog = null;
    protected AtomicBoolean mUploading = new AtomicBoolean(false);
    

    private BaseRunnerFragment mRunnerFragment = null;
    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Extract data included in the Intent
            Log.d(TAG, "context: " + context.getClass().getSimpleName() + ", intent: " + intent.toUri(Intent.URI_INTENT_SCHEME));
            setUploading(false);
            hideUploadingDialog();
            String text = intent.hasExtra(DispatchResponseReceiver.KEY_RESPONSE_MESSAGE)? intent.getStringExtra(DispatchResponseReceiver.KEY_RESPONSE_MESSAGE): "Upload Result Received: " + intent.getDataString();
            int result = intent.getIntExtra(DispatchService.RESPONSE_CODE, 400);
            if(result == 200)
                createUploadResultSuccessDialog(text).show();
            else
                createUploadResultFailDialog(text).show();
        }
    };

    /** {@inheritDoc} */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_PROGRESS);
    	Locales.updateLocale(this, getString(R.string.force_locale));
    }
    
    @Override
    public void onPause(){
        super.onPause();
    	Logf.D(TAG, "onPause()");
    	if(mUploading.get() && mUploadingDialog != null){
    		mUploadingDialog.dismiss();
    		mUploadingDialog = null;
    	}
    	LocalBroadcastManager.getInstance(this.getApplicationContext()).unregisterReceiver(mReceiver);
    }
    
    @Override
    public void onResume(){
        super.onResume();
    	Logf.D(TAG, "onResume()");
    	Logf.D(TAG, "uploading: " + mUploading.get());
    	if(mUploading.get()){
    		if(mUploadingDialog != null){
    			Logf.D(TAG, "mUploadingDialog != null && mUploading.get() = true");
    			mUploadingDialog.show();
    		} else {
    			Logf.D(TAG, "mUploadingDialog == null && mUploading.get() = true");
    			mUploadingDialog = new ProgressDialog(this);
    			mUploadingDialog.setTitle(R.string.general_upload_in_progress);
    			mUploadingDialog.setCancelable(false);
    			mUploadingDialog.show();
    		}
    	} else {
			Logf.D(TAG, "mUploading.get() = false");
    	}
        LocalBroadcastManager.getInstance(this.getApplicationContext()).registerReceiver(mReceiver, buildFilter());
    	
    }
    
    public IntentFilter buildFilter(){
    	IntentFilter filter = new IntentFilter(DispatchResponseReceiver.BROADCAST_RESPONSE);
        filter.addDataScheme(Encounters.CONTENT_URI.getScheme());
        try {
            
            filter.addDataType(Encounters.CONTENT_ITEM_TYPE);
        } catch (MalformedMimeTypeException e) {
        
        }
        return filter;
    }
    
    /** {@inheritDoc} */
    @Override
    public void onAttachFragment(Fragment fragment) {
        super.onAttachFragment(fragment);
        if (fragment instanceof BaseRunnerFragment) {
            mRunnerFragment = (BaseRunnerFragment) fragment;
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        Locales.updateLocale(this, getString(R.string.force_locale));
        //menu.add(0, OPTION_SAVE_EXIT, 0, getString(R.string.menu_save_exit));
        menu.add(0, OPTION_DISCARD_EXIT, 1, getString(R.string.menu_discard_exit));
        menu.add(0, OPTION_VIEW_PAGES, 2, getString(R.string.menu_view_pages));
        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                Constants.PREFERENCE_EDUCATION_RESOURCE, false))
            menu.add(0, OPTION_HELP, 3, "Help");
        return true;
    }

    ReentrantLock lock = new ReentrantLock();

    /** {@inheritDoc} */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        lock.lock();
        try {
            switch (item.getItemId()) {
                case OPTION_SAVE_EXIT:
                    mRunnerFragment.storeCurrentProcedure(false);
                    setResult(RESULT_CANCELED, null);
                    mRunnerFragment.logEvent(EventType.ENCOUNTER_SAVE_QUIT, "");
                    finish();
                    return true;
                case OPTION_DISCARD_EXIT:
                    mRunnerFragment.deleteCurrentProcedure();
                    mRunnerFragment.logEvent(EventType.ENCOUNTER_DISCARD, "");
                    setResult(RESULT_CANCELED, null);
                    finish();
                    return true;
                case OPTION_VIEW_PAGES:
                    this.wasOnDonePage = true;
                    mRunnerFragment.pageList();
                    return true;
                case OPTION_HELP:
                    mRunnerFragment.showInfo(Audience.WORKER);
                    return true;
            }
        } finally {
            lock.unlock();
        }
        return false;
    }

    /** The back key will activate previous page. */
    @Override
    public boolean onKeyDown(int keycode, KeyEvent e) {
        switch (keycode) {
            case KeyEvent.KEYCODE_BACK:
                mRunnerFragment.onBackButtonPressed(wasOnDonePage);
                wasOnDonePage = false;
                return true;
            default:
                return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_ALREADY_UPLOADED:
                return new AlertDialog.Builder(this)
                        .setTitle(getResources().getString(
                                R.string.general_alert))
                        .setMessage(getResources().getString(
                                R.string.dialog_already_uploaded))
                        .setNeutralButton(getResources().getString(R.string.general_ok),
                                new DialogInterface.OnClickListener()
                                {
                                    public void onClick(DialogInterface dialog, int whichButton) {
                                        // close without saving
                                        setResult(RESULT_OK, null);
                                    }
                                })
                        .setCancelable(false)
                        .create();

            case DIALOG_SIGNAL_STRENGTH:
            	break;
            case DIALOG_UPLOAD_RESULT:	
            
            default:
                break;
        }
        return null;
    }

    /** Handles launching data capture Activities. */
    @Override
    protected void onNewIntent(Intent intent) {
        Log.i(TAG, "data: " + intent.toUri(Intent.URI_INTENT_SCHEME));
        int description = intent.getExtras().getInt(INTENT_KEY_STRING, RUN_DISCARD);
        Log.i(TAG, "description = " + description);
        try {
            switch (description) {
                case 0: // intent comes from PictureElement to launch camera app
                    params = intent.getStringArrayExtra(PictureElement.PARAMS_NAME);
                    // For Android 1.1:
                    // Intent cameraIntent = new
                    // Intent("android.media.action.IMAGE_CAPTURE");

                    // For Android >=1.5:
                    Intent cameraIntent = new Intent(
                            MediaStore.ACTION_IMAGE_CAPTURE);

                    // EXTRA_OUTPUT is broken on a lot of phones. The HTC G1,
                    // Tattoo,
                    // and Wildfire return a majorly downsampled version of the
                    // image. In the HTC Sense UI, this is a bug with their
                    // camera.
                    // With vanilla Android, it's a bug in 1.6.

                    //Uri tempImageUri = Uri.fromFile(getTemporaryImageFile());
                    Uri tempImageUri = Uri.fromFile(getTemporaryImageFile(params[0],params[1], params[2]));
                    Log.d(TAG, "tempImageUri: " + tempImageUri);
                    mEncounterState = new Intent();
                    mEncounterState.setDataAndType(tempImageUri, "image/jpg");
                    // This extra tells the camera to return a larger image -
                    // only works in >=1.5
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, tempImageUri);
                    startActivityForResult(cameraIntent, CAMERA_INTENT_REQUEST_CODE);
                    break;
                case PLUGIN_INTENT_REQUEST_CODE:
                    Log.d(TAG, "Got request to start plugin activity.");
                    mEncounterState = new Intent();
                    mEncounterState.setDataAndType(intent.getData(),
                            intent.getType());
                    Log.d(TAG, "State: " + mEncounterState.toUri(
                            Intent.URI_INTENT_SCHEME));
                    Intent plug = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    // Add some state about the current encounter to the Intent
                    plug.putExtra("subject",
                            p.getPatientInfo().getPatientIdentifier());
                    plug.putExtra("encounter", EncounterDAO.getEncounterGuid(this,
                            thisSavedProcedure));
                    plug.putExtra("observation", intent.getStringExtra(
                            BinarySQLFormat.ELEMENT_ID));

                    Log.d(TAG, "Plug: " + plug.toUri(Intent.URI_INTENT_SCHEME));
                    startActivityForResult(plug, PLUGIN_INTENT_REQUEST_CODE);
                    break;
                case OBSERVATION_RESULT_CODE:
                	String id = intent.getStringExtra("id");
                	int page = intent.getIntExtra("page", -1);
                	String obs = intent.getStringExtra(Observations.Contract.VALUE);
                	ContentValues vals = new ContentValues();
                	vals.put(Observations.Contract.VALUE, obs);
                	//getContentResolver().update(intent.getData(), vals, null,null );
                	Log.e(TAG, String.format("Returned observation: { page: '%d', id: '%s', value: '%s'}", page, id,obs));
                	//setValue(page, id, obs);
                	//storeCurrentProcedure(false, true);
                	break;
                case RUN_DISCARD:
                	Intent dial = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                	// This is very forgiving of missing EXTRA_INTENT
                	try{
                		startActivityForResult(dial, RUN_DISCARD);
                	} catch (Exception e){
                		Log.w(TAG, "Bad Intent: " + dial);
                		e.printStackTrace();
                	}
                	break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            for (Object o : e.getStackTrace())
                Log.e(TAG, "...." + o);
            Toast.makeText(this, this.getString(R.string.msg_err_no_plugin),
                    Toast.LENGTH_SHORT).show();
        }
    }
    Uri tempImageUri = Uri.EMPTY;
    
    /** Handles the results of Activities launched for data capture. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            final Intent data)
    {
        Log.i(TAG, "data: " + ((data != null) ? data.toUri(Intent.URI_INTENT_SCHEME):"null"));
        Log.d(TAG, "Returned. requestCode: " + requestCode);
        Log.d(TAG, "......... resultCode : " + resultCode);
        Log.d(TAG, "......... obs        : " + mEncounterState.getData());
        Log.d(TAG, "......... type       : " + mEncounterState.getType());
        String answer = "";
        switch (resultCode) {
            case (RESULT_OK):
                try {
                    switch (requestCode) {
                    /*
                     * case (BARCODE_INTENT_REQUEST_CODE): String contents =
                     * data.getStringExtra("SCAN_RESULT"); String format =
                     * data.getStringExtra("SCAN_RESULT_FORMAT"); Log.i(TAG,
                     * "Got result from barcode intent: " + contents);
                     * ProcedurePage pp = p.current(); PatientIdElement
                     * patientId = pp.getPatientIdElement();
                     * patientId.setAndRefreshAnswer(contents); break;
                     */
                    // TODO the camera should get removed.
                        case (CAMERA_INTENT_REQUEST_CODE):
                            ImageProcessingTaskRequest request =
                                    new ImageProcessingTaskRequest();
                            request.savedProcedureId = params[0];
                            request.elementId = params[1];
                            //request.tempImageFile = getTemporaryImageFile();
                            request.tempImageFile = getTemporaryImageFile(params[0],params[1], params[2]);
                            request.c = this;
                            
                            request.intent = data;
                            Log.i(TAG, "savedProcedureId " + request.savedProcedureId
                                    + " and elementId " + request.elementId);

                            // Handles making a thumbnail of the image and
                            // moving it
                            // from the temporary location.
                            ImageProcessingTask imageTask = new ImageProcessingTask();
                            imageTask.execute(request);
                            break;
                        case (INFO_INTENT_REQUEST_CODE):
                            Log.d(TAG, "EducationResource intent: " + data.getType());
                            if (data.getType().contains("text/plain")) {
                                String text = data.getStringExtra("text");
                                String title = data.getStringExtra(Intent.EXTRA_TITLE);
                                SanaUtil.createDialog(this, title, text).show();
                            } else {
                                startActivity(data);
                            }
                            break;
                        case PLUGIN_INTENT_REQUEST_CODE:
                            Uri mObs = mEncounterState.getData();
                            String mObsType = mEncounterState.getType();
                            Intent result = PluginService.renderPluginActivityResult(
                                    getContentResolver(), data, mObs, mObsType);
                            String type = result.getType();
                            Uri rData = result.getData();

                            // Check if we get plain text first
                            if (type.equals("text/plain")) {
                                answer = rData.getFragment();

                                // Otherwise we have binary blob so we insert
                            } else {
                                Uri uri = BinaryDAO.updateOrCreate(
                                        getContentResolver(),
                                        mObs.getPathSegments().get(1),
                                        mObs.getPathSegments().get(2),
                                        rData, type);
                                Log.d(TAG, "Binary insert uri: " + uri.toString());
                                answer = BinaryDAO.getUUID(uri);
                                p.current().setElementValue(
                                        mEncounterState.getData().getPathSegments().get(2),
                                        answer);
                            }
                            break;

                        case OBSERVATION_RESULT_CODE:
                        	String id = data.getStringExtra("id");
                        	int page = data.getIntExtra("page", -1);
                        	String obs = data.getStringExtra(Observations.Contract.VALUE);
                        	Log.e(TAG, String.format("Returned observation: { page: '%d', id: '%s', value: '%s'}", page, id,obs));
                        	p.setValue(page, id, obs);
                        	break;
                        case RUN_DISCARD:
                        	Log.i(TAG, "Action completed successfully: " + data);
                        	break;
                        default:
                            Log.e(TAG, "Unknown activity");
                            answer = "";
                            p.current().setElementValue(
                                    mEncounterState.getData().getPathSegments().get(2),
                                    answer);
                            break;
                    }

                    Log.d(TAG, "Got answer: " + answer);
                    Log.w(TAG, mEncounterState.toUri(0));
                    //p.current().setElementValue(
                    //        mEncounterState.getData().getPathSegments().get(2),
                    //        answer);
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.e(TAG, "Error capturing answer from RESULT_OK: "
                            + e.toString());
                }
                break;
            default:
                Log.i(TAG, "Activity cancelled.");
                break;
        }

    }

    public boolean setValue(int pageIndex, String elementId, String value){
    	boolean result = false;
    	if (mRunnerFragment != null && mRunnerFragment instanceof BaseRunnerFragment) {
            result = mRunnerFragment.setValue(pageIndex, elementId, value);
    	}
    	return result;
    }
    
    public void storeCurrentProcedure(boolean finished, boolean skipHidden){
        if (mRunnerFragment != null) {
        	mRunnerFragment.storeCurrentProcedure(finished, skipHidden);
        }
    }
    
    protected final void makeText(String text){
		makeText(text, Toast.LENGTH_LONG);
	}
    
    protected final void makeText(String text, int duration){
    	Locales.updateLocale(this, getString(R.string.force_locale));
		Toast.makeText(this, text, duration).show();
	}
    
    protected final void makeText(int resId){
    	makeText(resId, Toast.LENGTH_SHORT);
	}
    
    protected final void makeText(int resId, int duration){
    	makeText(getString(resId), duration);
	}
    
    /** A static temporary image file
     * 
     * @return */
    protected static File getTemporaryImageFile() {
        return new File(Environment.getExternalStorageDirectory(), "sana.jpg");
    }
    
    protected static File getTemporaryImageFile(String encounter, String obsId, String objId) {
    	File dir = new File(Environment.getExternalStorageDirectory(), "sana/tmp/");
    	if(!dir.exists())
    		dir.mkdirs();
        return new File(dir, String.format("%s_%s-%s.jpg", encounter, obsId, objId));
    }
    
    protected static boolean removeTemporaryImageFile(String encounter, String obsId) {
    	File dir = new File(Environment.getExternalStorageDirectory(), "sana/tmp/");
    	if(!dir.exists())
    		dir.mkdirs();
        File tmp =  new File(dir, String.format("%s_%s.jpg", encounter, obsId));
        return tmp.delete();
    }
    
    /**
     * 
     * @param text
     * @return
     */
    protected final AlertDialog createUploadResultSuccessDialog(String text){
    	Locales.updateLocale(this,getString(R.string.force_locale));
    	return new AlertDialog.Builder(this)
    	.setTitle(getResources().getString(R.string.upload_status))
        .setMessage((TextUtils.isEmpty(text))?getResources().getString(R.string.upload_success):text)
            .setNeutralButton(getResources().getString(R.string.general_ok), new DialogInterface.OnClickListener(){
                    public void onClick(DialogInterface dialog, int whichButton) {// close without saving
                        setResult(RESULT_OK, null);
                        finish();
                }})
             .setCancelable(false)
             .create();
    }
    
    /**
     * 
     * @param text
     * @return
     */
    protected final AlertDialog createUploadResultFailDialog(String text){
    	Locales.updateLocale(this,getString(R.string.force_locale));
    	return new AlertDialog.Builder(this)
        	.setTitle(getResources().getString(R.string.upload_status))
            .setMessage((TextUtils.isEmpty(text))?getResources().getString(R.string.upload_fail):text)
            .setNeutralButton(getResources().getString(R.string.general_ok), new DialogInterface.OnClickListener(){
                    public void onClick(DialogInterface dialog, int whichButton) {
                    	// close without saving
                        //setResult(RESULT_OK, null);
                        //finish();
                }})
             .setCancelable(false)
             .create();
    }
    
    protected final void createSignalStrengthDialog(){
    	
    	
    }
    
    public void hideUploadingDialog(){
    	if(mUploadingDialog != null && mUploadingDialog.isShowing()){
    		mUploadingDialog.dismiss();
    		mUploadingDialog = null;
    	}
    }
    
    public void showUploadingDialog(){
    	if(mUploadingDialog != null && !mUploadingDialog.isShowing())
    		mUploadingDialog.show();
    	else{
    		mUploadingDialog = new ProgressDialog(this);
    		mUploadingDialog.setTitle(R.string.general_upload_in_progress);
    		mUploadingDialog.setCancelable(false);
    		mUploadingDialog.show();
    	}
    		
    }
    
    public void setUploading(boolean state){
    	mUploading.set(state);
    }
    
    public void onSaveInstanceState(Bundle outState){
    	super.onSaveInstanceState(outState);
    	outState.putBoolean("mUploading", mUploading.get());
    }
    
    public void onRestoreInstanceState(Bundle inState){
    	super.onRestoreInstanceState(inState);
    	if(inState != null){
    		mUploading.set(inState.getBoolean("mUploading", false));
    	} else {
    		mUploading.set(false);
    	}
    }
    
    private class GSMStateListener extends PhoneStateListener{
    	@Override
        public void onSignalStrengthsChanged(SignalStrength strength) {
        	Locales.updateLocale(BaseRunner.this, getString(R.string.force_locale));
        	String negativeText = getResources().getString(R.string.general_cancel);
        	String positiveText = getResources().getString(R.string.general_ok);
            super.onSignalStrengthsChanged(strength);
            new AlertDialog.Builder(BaseRunner.this)
                .setTitle("SNR")
                .setMessage(String.valueOf(strength.getGsmSignalStrength()))
                .setPositiveButton(positiveText, new DialogInterface.OnClickListener(){
                    public void onClick(DialogInterface dialog, int whichButton) {// close without saving
                        //setResult(RESULT_OK, null);
                        //finish();
                }})
                .setNegativeButton(negativeText, new DialogInterface.OnClickListener(){
                    public void onClick(DialogInterface dialog, int whichButton) {// close without saving
                        //setResult(RESULT_OK, null);
                        //finish();
                }})
                .create()
                .show();
        }
    }
    
    public void onProcedureComplete(Intent data){
    }
    
    public void onProcedureCancelled(String message){
    }
}
