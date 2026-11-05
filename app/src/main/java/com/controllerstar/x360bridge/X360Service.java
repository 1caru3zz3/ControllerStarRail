package com.controllerstar.x360bridge;

import android.content.Context;
import android.os.RemoteException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class X360Service extends IX360Service.Stub {
    public X360Service(Context context) {
    }

    @Override
    public String exec(String command) throws RemoteException {
        Process process;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
        } catch (IOException e) {
            return e.toString();
        }

        try {
            String output = readAll(process.getInputStream());
            String error = readAll(process.getErrorStream());
            int code = process.waitFor();
            return (output + error + "\nexit=" + code).trim();
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            return e.toString();
        } catch (IOException e) {
            return e.toString();
        }
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    private static String readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toString("UTF-8");
    }
}
