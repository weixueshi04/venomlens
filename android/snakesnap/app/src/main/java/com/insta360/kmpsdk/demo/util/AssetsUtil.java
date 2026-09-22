package com.insta360.kmpsdk.demo.util;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class AssetsUtil {

    public static void copyFilesFromAssets(Context context, String assetsPath, String savePath) {
        try {
            String[] fileNames = context.getAssets().list(assetsPath);
            if (fileNames.length > 0) {
                File file = new File(savePath);
                file.mkdirs();
                for (String fileName : fileNames) {
                    copyFilesFromAssets(context, assetsPath + "/" + fileName, savePath + "/" + fileName);
                }
            } else {
                InputStream is = context.getAssets().open(assetsPath);
                FileOutputStream fos = new FileOutputStream(savePath);
                byte[] buffer = new byte[1024];
                int byteCount = 0;
                while ((byteCount = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, byteCount);
                }
                fos.flush();
                is.close();
                fos.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
