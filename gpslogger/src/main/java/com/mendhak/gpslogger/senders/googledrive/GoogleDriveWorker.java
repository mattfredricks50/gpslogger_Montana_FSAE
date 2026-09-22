package com.mendhak.gpslogger.senders.googledrive;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.Strings;
import com.mendhak.gpslogger.common.Systems;
import com.mendhak.gpslogger.common.events.UploadEvents;
import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.loggers.Files;

import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationService;

import org.json.JSONObject;
import org.slf4j.Logger;

import java.io.File;
import java.net.URLEncoder;
import java.util.concurrent.atomic.AtomicBoolean;

import de.greenrobot.event.EventBus;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GoogleDriveWorker extends Worker {
    private static final Logger LOG = Logs.of(GoogleDriveWorker.class);

    private String googleDriveAccessToken;


    public GoogleDriveWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {

        String filePath = getInputData().getString("filePath");
        File fileToUpload = new File(filePath);
        reportProgress(fileToUpload.getName(), 0, fileToUpload.length());
        boolean success = true;
        String failureMessage = "";
        Throwable failureThrowable = null;


        AuthState authState = GoogleDriveManager.getAuthState();
        if (!authState.isAuthorized()) {
            EventBus.getDefault().post(new UploadEvents.GoogleDrive().failed("Could not upload to Google Drive. Not Authorized."));
        }

        final AtomicBoolean taskDone = new AtomicBoolean(false);
        PreferenceHelper preferenceHelper = PreferenceHelper.getInstance();

        try {
            AuthorizationService authorizationService = GoogleDriveManager.getAuthorizationService(AppSettings.getInstance());

            // The performActionWithFreshTokens seems to happen on a UI thread! (Why??)
            // So I can't do network calls on this thread.
            // Instead, updating a class level variable, and waiting for it afterwards.
            // https://github.com/openid/AppAuth-Android/issues/123
            authState.performActionWithFreshTokens(authorizationService, new AuthState.AuthStateAction() {
                @Override
                public void execute(@Nullable String accessToken, @Nullable String idToken, @Nullable AuthorizationException ex) {
                    if (ex != null) {
                        EventBus.getDefault().post(new UploadEvents.GoogleDrive().failed(ex.toJsonString(), ex));
                        taskDone.set(true);
                        LOG.error(ex.toJsonString(), ex);
                        return;
                    }
                    googleDriveAccessToken = accessToken;
                    taskDone.set(true);
                }
            });

            // Wait for the performActionWithFreshTokens.execute callback
            // (which happens on the UI thread for some reason) to complete.
            while (!taskDone.get()) {
                Thread.sleep(500);
            }

            if (Strings.isNullOrEmpty(googleDriveAccessToken)) {
                LOG.error("Failed to fetch Access Token for Google Drive. Stopping this job.");
                DriveUploadStatus.recordFailure(getApplicationContext(), filePath, fileToUpload.getName(),
                        "Not signed in to Google Drive");
                return Result.failure();
            }

            // A folder link or id: setting targets an existing folder (eg. in a Shared Drive) directly.
            // Otherwise figure out the Folder ID from the path; recursively create if it doesn't exist.
            String folderPath = preferenceHelper.getGoogleDriveFolderPath();
            String latestFolderId = DriveFolderRef.parseFolderId(folderPath);
            if (!Strings.isNullOrEmpty(latestFolderId)) {
                LOG.debug("Uploading to existing folder ID " + latestFolderId);
            } else {
                String[] pathParts = folderPath.split("/");
                String parentFolderId = null;
                for (String part : pathParts) {
                    latestFolderId = getFileIdFromFileName(googleDriveAccessToken, part, parentFolderId);
                    if (!Strings.isNullOrEmpty(latestFolderId)) {
                        LOG.debug("Folder " + part + " found, folder ID is " + latestFolderId);
                    } else {
                        LOG.debug("Folder " + part + " not found, creating.");
                        latestFolderId = createEmptyFile(googleDriveAccessToken, part,
                                "application/vnd.google-apps.folder", Strings.isNullOrEmpty(parentFolderId) ? "root" : parentFolderId);
                    }
                    parentFolderId = latestFolderId;
                }
            }

            String gpsLoggerFolderId = latestFolderId;

            if (Strings.isNullOrEmpty(gpsLoggerFolderId)) {
                failureMessage = "Could not create folder";
                success = false;
            }
            else {
                // Now search for the file
                String gpxFileId = getFileIdFromFileName(googleDriveAccessToken, fileToUpload.getName(), gpsLoggerFolderId);

                if (Strings.isNullOrEmpty(gpxFileId)) {
                    LOG.debug("Creating an empty file first.");
                    gpxFileId = createEmptyFile(googleDriveAccessToken, fileToUpload.getName(), Files.getMimeTypeFromFileName(fileToUpload.getName()), gpsLoggerFolderId);

                    if (Strings.isNullOrEmpty(gpxFileId)) {
                        failureMessage = "Could not create file";
                        success = false;
                    }
                }

                // The above empty file creation needs to happen first - this shouldn't be an 'else' to the above if.
                if (!Strings.isNullOrEmpty(gpxFileId)) {
                    LOG.debug("Uploading file contents");
                    updateFileContents(googleDriveAccessToken, gpxFileId, fileToUpload);
                }

            }

        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            success = false;
            failureMessage = e.getMessage();
            failureThrowable = e;
        }

        if(success){
            DriveUploadStatus.recordSuccess(getApplicationContext(), fileToUpload.getName());
            // Notify internal listeners
            EventBus.getDefault().post(new UploadEvents.GoogleDrive().succeeded());
            // Notify external listeners
            Systems.sendFileUploadedBroadcast(getApplicationContext(), new String[]{fileToUpload.getAbsolutePath()}, "googledrive");
            return Result.success();
        }

        if(getRunAttemptCount() < getRetryLimit()){
            LOG.warn(String.format("Google Drive - attempt %d of %d failed, will retry", getRunAttemptCount(), getRetryLimit()));
            return Result.retry();
        }

        if(failureThrowable == null) {
            failureThrowable = new Exception(failureMessage);
        }

        DriveUploadStatus.recordFailure(getApplicationContext(), filePath, fileToUpload.getName(), failureMessage);
        EventBus.getDefault()
                .post(new UploadEvents.GoogleDrive().failed(failureMessage, failureThrowable));
        return Result.failure();

    }

    private String getFileIdFromFileName(String accessToken, String fileName, String inFolderId) throws Exception {
        String fileId = "";
        fileName = URLEncoder.encode(fileName, "UTF-8");

        String inFolderParam = "";
        if (!Strings.isNullOrEmpty(inFolderId)) {
            // corpora=allDrives so the lookup also sees Shared Drive folders
            inFolderParam = "+and+'" + inFolderId + "'+in+parents&corpora=allDrives";
        }
        String searchUrl = "https://www.googleapis.com/drive/v3/files?q=name%20%3D%20%27" + fileName + "%27%20and%20trashed%20%3D%20false" + inFolderParam
                + "&supportsAllDrives=true&includeItemsFromAllDrives=true";
        OkHttpClient client = new OkHttpClient();
        Request.Builder requestBuilder = new Request.Builder().url(searchUrl);

        requestBuilder.addHeader("Authorization", "Bearer " + accessToken);

        Request request = requestBuilder.build();
        Response response = client.newCall(request).execute();
        String fileMetadata = response.body().string();
        LOG.debug(fileMetadata);
        response.body().close();
        JSONObject fileMetadataJson = new JSONObject(fileMetadata);
        if (!fileMetadataJson.has("files")) {
            // eg. 404 when the account can't see the folder
            throw new Exception("Google Drive search failed: " + fileMetadata);
        }
        if (fileMetadataJson.getJSONArray("files") != null && fileMetadataJson.getJSONArray("files").length() > 0) {
            fileId = fileMetadataJson.getJSONArray("files").getJSONObject(0).get("id").toString();
            LOG.debug("Found file with ID " + fileId);
        }

        return fileId;
    }

    private String createEmptyFile(String accessToken, String fileName, String mimeType, String parentFolderId) throws Exception {

        String fileId = null;
        String createFileUrl = "https://www.googleapis.com/drive/v3/files?supportsAllDrives=true";

        String createFilePayload = "   {\n" +
                "             \"name\": \"" + fileName + "\",\n" +
                "             \"mimeType\": \"" + mimeType + "\",\n" +
                "             \"parents\": [\"" + parentFolderId + "\"]\n" +
                "            }";


        OkHttpClient client = new OkHttpClient();
        Request.Builder requestBuilder = new Request.Builder().url(createFileUrl);

        requestBuilder.addHeader("Authorization", "Bearer " + accessToken);
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), createFilePayload);
        requestBuilder = requestBuilder.method("POST", body);


        Request request = requestBuilder.build();
        Response response = client.newCall(request).execute();
        String fileMetadata = response.body().string();
        LOG.debug(fileMetadata);
        response.body().close();

        JSONObject fileMetadataJson = new JSONObject(fileMetadata);
        if (!fileMetadataJson.has("id")) {
            // eg. 403 when the account can't add files to the folder
            throw new Exception("Google Drive create failed: " + fileMetadata);
        }
        fileId = fileMetadataJson.getString("id");

        return fileId;
    }

    private String updateFileContents(String accessToken, String gpxFileId, File fileToUpload) throws Exception {
        String fileId = null;

        String fileUpdateUrl = "https://www.googleapis.com/upload/drive/v3/files/" + gpxFileId + "?uploadType=media&supportsAllDrives=true";

        OkHttpClient client = new OkHttpClient();
        Request.Builder requestBuilder = new Request.Builder().url(fileUpdateUrl);

        requestBuilder.addHeader("Authorization", "Bearer " + accessToken);
        // Stream from disk rather than reading the whole file into memory; IMU logs are large.
        final String name = fileToUpload.getName();
        RequestBody body = new ProgressFileBody(MediaType.parse(Files.getMimeTypeFromFileName(name)), fileToUpload,
                new ProgressFileBody.Listener() {
                    @Override
                    public void onProgress(long sent, long total) {
                        reportProgress(name, sent, total);
                    }
                });
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
            requestBuilder.addHeader("X-HTTP-Method-Override", "PATCH");
        }
        requestBuilder = requestBuilder.method("PATCH", body);

        Request request = requestBuilder.build();
        Response response = client.newCall(request).execute();
        String fileMetadata = response.body().string();
        LOG.debug(fileMetadata);
        response.body().close();

        JSONObject fileMetadataJson = new JSONObject(fileMetadata);
        fileId = fileMetadataJson.getString("id");

        return fileId;
    }

    private void reportProgress(String fileName, long sent, long total) {
        setProgressAsync(new Data.Builder()
                .putString(DriveUploadStatus.PROGRESS_FILE, fileName)
                .putLong(DriveUploadStatus.PROGRESS_SENT, sent)
                .putLong(DriveUploadStatus.PROGRESS_TOTAL, total)
                .build());
    }

    protected int getRetryLimit() {
        return 3;
    }
}
