package net.hockeyapp.android;

import java.io.*;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;


import android.preference.PreferenceManager;
import net.hockeyapp.android.objects.CrashManagerUserInput;
import net.hockeyapp.android.objects.CrashMetaData;
import net.hockeyapp.android.utils.PrefsUtil;

import net.hockeyapp.android.utils.Util;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.util.Log;

/**
 * <h3>Description</h3>
 * 
 * The crash manager sets an exception handler to catch all unhandled 
 * exceptions. The handler writes the stack trace and additional meta data to 
 * a file. If it finds one or more of these files at the next start, it shows 
 * an alert dialog to ask the user if he want the send the crash data to 
 * HockeyApp. 
 * 
 * <h3>License</h3>
 * 
 * <pre>
 * Copyright (c) 2011-2014 Bit Stadium GmbH
 * 
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 * </pre>
 *
 * @author Thomas Dohmke
 **/
public class CrashManager {
  /**
   * App identifier from HockeyApp.
   */
  private static String identifier = null;
  
  /**
   * URL of HockeyApp service.
   */
  private static String urlString = null;

  /**
   * Stack traces are currently submitted
   */
  private static boolean submitting = false;

  /**
   * Shared preferences key for always send dialog button.
   */
  private static final String ALWAYS_SEND_KEY = "always_send_crash_reports";

  /**
   * Registers new crash manager and handles existing crash logs.
   * 
   * @param context The context to use. Usually your Activity object.
   * @param appIdentifier App ID of your app on HockeyApp.
   */
  public static void register(Context context, String appIdentifier) {
    register(context, Constants.BASE_URL, appIdentifier, null);
  }

  /**
   * Registers new crash manager and handles existing crash logs.
   * 
   * @param context The context to use. Usually your Activity object.
   * @param appIdentifier App ID of your app on HockeyApp.
   * @param listener Implement for callback functions.
   */
  public static void register(Context context, String appIdentifier, CrashManagerListener listener) {
    register(context, Constants.BASE_URL, appIdentifier, listener);
  }

  /**
   * Registers new crash manager and handles existing crash logs.
   * 
   * @param context The context to use. Usually your Activity object.
   * @param urlString URL of the HockeyApp server.
   * @param appIdentifier App ID of your app on HockeyApp.
   * @param listener Implement for callback functions.
   */
  public static void register(Context context, String urlString, String appIdentifier, CrashManagerListener listener) {
    initialize(context, urlString, appIdentifier, listener, false);
    execute(context, listener);
  }

  /**
   * Initializes the crash manager, but does not handle crash log. Use this 
   * method only if you want to split the process into two parts, i.e. when
   * your app has multiple entry points. You need to call the method 'execute' 
   * at some point after this method. 
   * 
   * @param context The context to use. Usually your Activity object.
   * @param appIdentifier App ID of your app on HockeyApp.
   * @param listener Implement for callback functions.
   */
  public static void initialize(Context context, String appIdentifier, CrashManagerListener listener) {
    initialize(context, Constants.BASE_URL, appIdentifier, listener, true);
  }

  /**
   * Initializes the crash manager, but does not handle crash log. Use this 
   * method only if you want to split the process into two parts, i.e. when
   * your app has multiple entry points. You need to call the method 'execute' 
   * at some point after this method. 
   * 
   * @param context The context to use. Usually your Activity object.
   * @param urlString URL of the HockeyApp server.
   * @param appIdentifier App ID of your app on HockeyApp.
   * @param listener Implement for callback functions.
   */
  public static void initialize(Context context, String urlString, String appIdentifier, CrashManagerListener listener) {
    initialize(context, urlString, appIdentifier, listener, true);
  }

  /**
   * Executes the crash manager. You need to call this method if you have used
   * the method 'initialize' before.
   * 
   * @param context The context to use. Usually your Activity object.
   * @param listener Implement for callback functions.
   */
  @SuppressWarnings("deprecation")
  public static void execute(Context context, CrashManagerListener listener) {
    Boolean ignoreDefaultHandler = (listener != null) && (listener.ignoreDefaultHandler());
    WeakReference<Context> weakContext = new WeakReference<Context>(context);
    
    int foundOrSend = hasStackTraces(weakContext);
    if (foundOrSend == 1) {
      Boolean autoSend = false;
      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
      autoSend |= prefs.getBoolean(ALWAYS_SEND_KEY, false);

      if (listener != null) {
        autoSend |= listener.shouldAutoUploadCrashes();
        autoSend |= listener.onCrashesFound();
        
        listener.onNewCrashesFound();
      }
      
      if (!autoSend) {
        showDialog(weakContext, listener, ignoreDefaultHandler);
      }
      else {
        sendCrashes(weakContext, listener, ignoreDefaultHandler);
      }
    }
    else if (foundOrSend == 2) {
      if (listener != null) {
        listener.onConfirmedCrashesFound();
      }
      
      sendCrashes(weakContext, listener, ignoreDefaultHandler);
    }
    else {
      registerHandler(weakContext, listener, ignoreDefaultHandler);
    }
  }
  
  /**
   * Checks if there are any saved stack traces in the files dir.
   * 
   * @param weakContext The context to use. Usually your Activity object.
   * @return 0 if there are no stack traces,
   *         1 if there are any new stack traces, 
   *         2 if there are confirmed stack traces
   */
  public static int hasStackTraces(WeakReference<Context> weakContext) {
    String[] filenames = searchForStackTraces();
    List<String> confirmedFilenames = null;
    int result = 0;
    if ((filenames != null) && (filenames.length > 0)) {
      try {
        Context context = null;
        if (weakContext != null) {
          context = weakContext.get();
          if (context != null) {
            SharedPreferences preferences = context.getSharedPreferences("HockeySDK", Context.MODE_PRIVATE);
            confirmedFilenames = Arrays.asList(preferences.getString("ConfirmedFilenames", "").split("\\|"));    
          }
        }
        
      }
      catch (Exception e) {
        // Just in case, we catch all exceptions here
      }

      if (confirmedFilenames != null) {
        result = 2;
        
        for (String filename : filenames) {
          if (!confirmedFilenames.contains(filename)) {
            result = 1;
            break;
          }
        }
      }
      else {
        result = 1;
      }
    }

    return result;
  }

  /**
   * Submits all stack traces in the files dir to HockeyApp.
   *
   * @param weakContext The context to use. Usually your Activity object.
   * @param listener Implement for callback functions.
   */
  public static void submitStackTraces(WeakReference<Context> weakContext, CrashManagerListener listener) {
    submitStackTraces(weakContext, listener, null);
  }
  /**
   * Submits all stack traces in the files dir to HockeyApp.
   *
   * @param weakContext The context to use. Usually your Activity object.
   * @param listener Implement for callback functions.
   * @param crashMetaData The crashMetaData, provided by the user.
   */
  public static void submitStackTraces(WeakReference<Context> weakContext, CrashManagerListener listener, CrashMetaData crashMetaData) {
    String[] list = searchForStackTraces();
    Boolean successful = false;

    if ((list != null) && (list.length > 0)) {
      Log.d(Constants.TAG, "Found " + list.length + " stacktrace(s).");

      for (int index = 0; index < list.length; index++) {
        try {
          // Read contents of stack trace
          String filename = list[index];
          String stacktrace = contentsOfFile(weakContext, filename);
          if (stacktrace.length() > 0) {
            // Transmit stack trace with POST request

            Log.d(Constants.TAG, "Transmitting crash data: \n" + stacktrace);

            // Retrieve user ID and contact information if given
            String userID =  contentsOfFile(weakContext, filename.replace(".stacktrace", ".user"));
            String contact = contentsOfFile(weakContext, filename.replace(".stacktrace", ".contact"));

            if (crashMetaData != null) {
              final String crashMetaDataUserID = crashMetaData.getUserID();
              if (crashMetaDataUserID != null && crashMetaDataUserID.length() > 0) {
                userID = crashMetaDataUserID;
              }
              final String crashMetaDataContact = crashMetaData.getUserEmail();
              if (crashMetaDataContact != null && crashMetaDataContact.length() > 0) {
                contact = crashMetaDataContact;
              }
            }

            // Append application log to user provided description if present, if not, just send application log
            final String applicationLog = contentsOfFile(weakContext, filename.replace(".stacktrace", ".description"));
            String description = crashMetaData != null ? crashMetaData.getUserDescription() : "";
            if (applicationLog != null && applicationLog.length() > 0) {
              if (description != null && description.length() > 0) {
                description = String.format("%s\n\nLog:\n%s", description, applicationLog);
              } else {
                description = String.format("Log:\n%s", applicationLog);
              }
            }

            Map<String, String> parameters = new HashMap<String, String>();

            parameters.put("raw", stacktrace);
            parameters.put("userID", userID);
            parameters.put("contact", contact);
            parameters.put("description", description);
            parameters.put("sdk", Constants.SDK_NAME);
            parameters.put("sdk_version", Constants.SDK_VERSION);

            HttpURLConnection urlConnection = (HttpURLConnection) new URL(getURLString()).openConnection();
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            OutputStream outputStream = urlConnection.getOutputStream();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8"));
            writer.write(Util.getFormString(parameters));

            urlConnection.connect();
            successful = true;

          }
        }
        catch (Exception e) {
          e.printStackTrace();
        }
        finally {
          if (successful) {
            Log.d(Constants.TAG, "Transmission succeeded");
            deleteStackTrace(weakContext, list[index]);

            if (listener != null) {
              listener.onCrashesSent();
              deleteRetryCounter(weakContext, list[index], listener.getMaxRetryAttempts());
            }
          }
          else {
            Log.d(Constants.TAG, "Transmission failed, will retry on next register() call");
            if (listener != null) {
              listener.onCrashesNotSent();
              updateRetryCounter(weakContext, list[index], listener.getMaxRetryAttempts());
            }
          }
        }
      }
    }
  }

  /**
   * Deletes all stack traces and meta files from files dir.
   * 
   * @param weakContext The context to use. Usually your Activity object.
   */
  public static void deleteStackTraces(WeakReference<Context> weakContext) {
    String[] list = searchForStackTraces();

    if ((list != null) && (list.length > 0)) {
      Log.d(Constants.TAG, "Found " + list.length + " stacktrace(s).");

      for (int index = 0; index < list.length; index++) {
        try {
          Context context = null;
          if (weakContext != null) {
            Log.d(Constants.TAG, "Delete stacktrace " + list[index] + ".");
            deleteStackTrace(weakContext, list[index]);

            context = weakContext.get();
            if (context != null) {
              context.deleteFile(list[index]);
            }
          }
        }
        catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
  }

  /**
   * Provides an interface to pass user input from a custom alert to a crash report
   *
   * @param userInput Defines the users action whether to send, always send, or not to send the crash report.
   * @param userProvidedMetaData The content of this optional CrashMetaData instance will be attached to the crash report
   *                             and allows to ask the user for e.g. additional comments or info.
   * @param listener an optional crash manager listener to use.
   * @param weakContext The context to use. Usually your Activity object.
   * @param ignoreDefaultHandler whether to ignore the default exception handler.
   * @return true if the input is a valid option and successfully triggered further processing of the crash report.
   *
   * @see CrashManagerUserInput
   * @see CrashMetaData
   * @see CrashManagerListener
   */
  public static boolean handleUserInput(final CrashManagerUserInput userInput,
      final CrashMetaData userProvidedMetaData, final CrashManagerListener listener,
      final WeakReference<Context> weakContext, final boolean ignoreDefaultHandler) {
    switch (userInput) {
      case CrashManagerUserInputDontSend:
        if (listener != null) {
          listener.onUserDeniedCrashes();
        }

        deleteStackTraces(weakContext);
        registerHandler(weakContext, listener, ignoreDefaultHandler);
        return true;
      case CrashManagerUserInputAlwaysSend:
        Context context = null;
        if (weakContext != null) {
          context = weakContext.get();
        }

        if (context == null) {
          return false;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean(ALWAYS_SEND_KEY, true).commit();

        sendCrashes(weakContext, listener, ignoreDefaultHandler, userProvidedMetaData);
        return true;
      case CrashManagerUserInputSend:
        sendCrashes(weakContext, listener, ignoreDefaultHandler, userProvidedMetaData);
        return true;
      default:
        return false;
    }
  }

  /**
   * Clears the preference to always send crashes. The next time the user
   * sees a crash and restarts the app, they will see the dialog again to 
   * send the crash.
   *
   * @param weakContext The context to use. Usually your Activity object.
   */
  public static void resetAlwaysSend(final WeakReference<Context> weakContext) {
    Context context = null;
    if (weakContext != null) {
      context = weakContext.get();

      if (context != null) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().remove(ALWAYS_SEND_KEY).commit();
      }
    }
  }

  /**
   * Private method to initialize the crash manager. This method has an
   * additional parameter to decide whether to register the exception handler
   * at the end or not.
   */
  private static void initialize(Context context, String urlString, String appIdentifier, CrashManagerListener listener, boolean registerHandler) {
    if (context != null) {
      CrashManager.urlString = urlString;
      CrashManager.identifier = Util.sanitizeAppIdentifier(appIdentifier);
  
      Constants.loadFromContext(context);
      
      if (CrashManager.identifier == null) {
        CrashManager.identifier = Constants.APP_PACKAGE;
      }
      
      if (registerHandler) {
        Boolean ignoreDefaultHandler = (listener != null) && (listener.ignoreDefaultHandler());
        WeakReference<Context> weakContext = new WeakReference<Context>(context); 
        registerHandler(weakContext, listener, ignoreDefaultHandler);
      }
    }
  }

  /**
   * Shows a dialog to ask the user whether he wants to send crash reports to 
   * HockeyApp or delete them.
   */
  private static void showDialog(final WeakReference<Context> weakContext, final CrashManagerListener listener, final boolean ignoreDefaultHandler) {
    Context context = null;
    if (weakContext != null) {
      context = weakContext.get();
    }

    if (context == null) {
      return;
    }

    if (listener != null && listener.onHandleAlertView()) {
      return;
    }

    AlertDialog.Builder builder = new AlertDialog.Builder(context);
    builder.setTitle(Strings.get(listener, Strings.CRASH_DIALOG_TITLE_ID));
    builder.setMessage(Strings.get(listener, Strings.CRASH_DIALOG_MESSAGE_ID));

    builder.setNegativeButton(Strings.get(listener, Strings.CRASH_DIALOG_NEGATIVE_BUTTON_ID), new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int which) {
        handleUserInput(CrashManagerUserInput.CrashManagerUserInputDontSend, null, listener, weakContext, ignoreDefaultHandler);
      }
    });

    builder.setNeutralButton(Strings.get(listener, Strings.CRASH_DIALOG_NEUTRAL_BUTTON_ID), new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int which) {
        handleUserInput(CrashManagerUserInput.CrashManagerUserInputAlwaysSend, null, listener, weakContext, ignoreDefaultHandler);
      }
    });

    builder.setPositiveButton(Strings.get(listener, Strings.CRASH_DIALOG_POSITIVE_BUTTON_ID), new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int which) {
        handleUserInput(CrashManagerUserInput.CrashManagerUserInputSend, null, listener,
            weakContext, ignoreDefaultHandler);
      }
    });

    builder.create().show();
  }

  /**
   * Starts thread to send crashes to HockeyApp, then registers the exception
   * handler.
   */
  private static void sendCrashes(final WeakReference<Context> weakContext, final CrashManagerListener listener, final boolean ignoreDefaultHandler) {
    sendCrashes(weakContext, listener, ignoreDefaultHandler, null);
  }

  /**
   * Starts thread to send crashes to HockeyApp, then registers the exception
   * handler.
   */
  private static void sendCrashes(final WeakReference<Context> weakContext, final CrashManagerListener listener, final boolean ignoreDefaultHandler, final CrashMetaData crashMetaData) {
    saveConfirmedStackTraces(weakContext);
    registerHandler(weakContext, listener, ignoreDefaultHandler);

    if (!submitting) {
      submitting = true;

      new Thread() {
        @Override
        public void run() {
          submitStackTraces(weakContext, listener, crashMetaData);
          submitting = false;
        }
      }.start();
    }
  }

  /**
   * Registers the exception handler. 
   */
  private static void registerHandler(WeakReference<Context> weakContext, CrashManagerListener listener, boolean ignoreDefaultHandler) {
    if ((Constants.APP_VERSION != null) && (Constants.APP_PACKAGE != null)) {
      // Get current handler
      UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
      if (currentHandler != null) {
        Log.d(Constants.TAG, "Current handler class = " + currentHandler.getClass().getName());
      }
  
      // Update listener if already registered, otherwise set new handler 
      if (currentHandler instanceof ExceptionHandler) {
        ((ExceptionHandler)currentHandler).setListener(listener);
      }
      else {
        Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler(currentHandler, listener, ignoreDefaultHandler));
      }
    }
    else {
      Log.d(Constants.TAG, "Exception handler not set because version or package is null.");
    }
  }

  /**
   * Returns the complete URL for the HockeyApp API. 
   */
  private static String getURLString() {
    return urlString + "api/2/apps/" + identifier + "/crashes/";      
  }

  /**
   * Update the retry attempts count for this crash stacktrace.
   */
  private static void updateRetryCounter(WeakReference<Context> weakContext, String filename, int maxRetryAttempts) {
    if (maxRetryAttempts == -1) {
        return;
    }

    Context context = null;
    if (weakContext != null) {
      context = weakContext.get();
      if (context != null) {
        SharedPreferences preferences = context.getSharedPreferences("HockeySDK", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();

        int retryCounter = preferences.getInt("RETRY_COUNT: "+ filename, 0);
        if (retryCounter >= maxRetryAttempts){
          deleteStackTrace(weakContext, filename);
          deleteRetryCounter(weakContext, filename, maxRetryAttempts);
        }
        else {
          editor.putInt("RETRY_COUNT: "+ filename, retryCounter + 1);
          editor.commit();
        }
      }
    }
  }

  /**
   * Delete the retry counter if stacktrace is uploaded or retry limit is 
   * reached.
   */
  private static void deleteRetryCounter(WeakReference<Context> weakContext, String filename, int maxRetryAttempts) {
    Context context = null;
    if (weakContext != null) {
      context = weakContext.get();
      if (context != null) {
        SharedPreferences preferences = context.getSharedPreferences("HockeySDK", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove("RETRY_COUNT: "+ filename);
        editor.commit();
      }
    }
  }

  /**
   * Deletes the give filename and all corresponding files (same name, 
   * different extension).
   */
  private static void deleteStackTrace(WeakReference<Context> weakContext, String filename) {
    Context context = null;
    if (weakContext != null) {
      context = weakContext.get();
      if (context != null) {
        context.deleteFile(filename);
        
        String user = filename.replace(".stacktrace", ".user");
        context.deleteFile(user);
        
        String contact = filename.replace(".stacktrace", ".contact");
        context.deleteFile(contact);
        
        String description = filename.replace(".stacktrace", ".description");
        context.deleteFile(description);
      }
    }
  }

  /**
   * Returns the content of a file as a string. 
   */
  private static String contentsOfFile(WeakReference<Context> weakContext, String filename) {
    Context context = null;
    if (weakContext != null) {
      context = weakContext.get();
      if (context != null) {
        StringBuilder contents = new StringBuilder();
        BufferedReader reader = null;
        try {
          reader = new BufferedReader(new InputStreamReader(context.openFileInput(filename)));
          String line = null;
          while ((line = reader.readLine()) != null) {
            contents.append(line);
            contents.append(System.getProperty("line.separator"));
          }
        }
        catch (FileNotFoundException e) {
        }
        catch (IOException e) {
          e.printStackTrace();
        }
        finally {
          if (reader != null) {
            try { 
              reader.close(); 
            } 
            catch (IOException ignored) { 
            }
          }
        }
        
        return contents.toString();        
      }
    }
    
    return null;
  }
  
  /**
   * Saves the list of the stack traces' file names in shared preferences.  
   */
  private static void saveConfirmedStackTraces(WeakReference<Context> weakContext) {
    Context context = null;
    if (weakContext != null) {
      context = weakContext.get();
      if (context != null) {
        try {
          String[] filenames = searchForStackTraces();
          SharedPreferences preferences = context.getSharedPreferences("HockeySDK", Context.MODE_PRIVATE);
          Editor editor = preferences.edit();
          editor.putString("ConfirmedFilenames", joinArray(filenames, "|"));
          PrefsUtil.applyChanges(editor);
        }
        catch (Exception e) {
          // Just in case, we catch all exceptions here
        }
      }
    }
  }
  
  /**
   * Returns a string created by each element of the array, separated by 
   * delimiter. 
   */
  private static String joinArray(String[] array, String delimiter) {
    StringBuffer buffer = new StringBuffer();
    for (int index = 0; index < array.length; index++) {
      buffer.append(array[index]);
      if (index < array.length - 1) {
        buffer.append(delimiter);
      }
    }
    return buffer.toString();
  }

  /**
   * Searches .stacktrace files and returns then as array. 
   */
  private static String[] searchForStackTraces() {
    if (Constants.FILES_PATH != null) {
      Log.d(Constants.TAG, "Looking for exceptions in: " + Constants.FILES_PATH);
  
      // Try to create the files folder if it doesn't exist
      File dir = new File(Constants.FILES_PATH + "/");
      boolean created = dir.mkdir();
      if (!created && !dir.exists()) {
        return new String[0];
      }
  
      // Filter for ".stacktrace" files
      FilenameFilter filter = new FilenameFilter() { 
        public boolean accept(File dir, String name) {
          return name.endsWith(".stacktrace"); 
        } 
      }; 
      return dir.list(filter);
    }
    else {
      Log.d(Constants.TAG, "Can't search for exception as file path is null.");
      return null;
    }
  }
}