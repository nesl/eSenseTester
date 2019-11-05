package com.example.esensetester;

import android.os.Environment;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class dataExporter {

    final String TAG = "DBG-DATAEXPORTER:";

    File mExportRoot;
    File mExportDir;
    int currentSession = 0;

    //String mCurrentDateString = "";

    public dataExporter() {

        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            Log.d(TAG, "No SD card, can't export Data");
        } else {
            //We use the Download directory for saving our .csv file.
            mExportRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            mExportDir = new File(mExportRoot, "ESENSE_DATA");
            if (!mExportDir.exists()) {
                mExportDir.mkdirs();
            }
        }

        currentSession = findCurrentSession();
    }


    public void directWriteToFile(String packet) {

        File file;
        PrintWriter printWriter = null;

        try {
            file = new File(mExportDir, "RAW_" + Integer.toString(currentSession) + ".txt");
            file.createNewFile();
            printWriter = new PrintWriter(new FileWriter(file, true));


            printWriter.println(packet); //write the record to the mood textfile
            Log.d(TAG, "Successfully wrote String of length" + Long.toString(packet.length()));
        } catch (Exception exc) {
            //if there are any exceptions, return false
            Log.d(TAG, exc.getMessage());
        } finally {
            if (printWriter != null) printWriter.close();
        }
    }

    private int findCurrentSession() {
        File[] files = mExportDir.listFiles();
        Log.d(TAG,  "Size: "+ files.length);
        return files.length;
    }
}
