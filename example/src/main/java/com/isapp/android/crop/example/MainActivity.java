package com.isapp.android.crop.example;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import com.isapp.android.crop.CropImageView;
import com.isapp.android.crop.Cropper;

import java.io.File;

public class MainActivity extends Activity {
    private static final int SELECT_PHOTO_REQUEST_CODE = 1001;

    private CropImageView cropperView;
    private ImageView resultView;

    private Cropper cropper;

    private Menu menu;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cropperView = (CropImageView) findViewById(R.id.cropper);
        resultView = (ImageView) findViewById(R.id.result_image);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        this.menu = menu;
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_select) {
            resultView.setImageDrawable(null);
            Intent intent = new Intent();
            intent.setType("image/*");
            intent.setAction(Intent.ACTION_GET_CONTENT);
            startActivityForResult(Intent.createChooser(intent, "Select Picture"), SELECT_PHOTO_REQUEST_CODE);
            return true;
        }
        else if(item.getItemId() == R.id.action_crop) {
            if (cropper != null) {
                new AsyncTask<Void, Void, Void>() {
                    @Override
                    protected Void doInBackground(Void... params) {
                        cropper.save();
                        return null;
                    }
                }.execute();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        if (requestCode == SELECT_PHOTO_REQUEST_CODE && resultCode == RESULT_OK) {
            final File outputFile = new File(getExternalFilesDir(null), "output");
            if(cropper != null) {
                cropper.release();
            }
            else {
                menu.findItem(R.id.action_crop).setVisible(true);
            }
            cropper = new Cropper.Builder(cropperView, result.getData(), Uri.parse(outputFile.toURI().toString()))
                .asSquare()
                .withCropFinishedListener(new Cropper.OnCropFinishedListener() {
                    @Override
                    public void onCropFinished(Uri output) {
                        cropperView.setVisibility(View.GONE);
                        resultView.setVisibility(View.VISIBLE);
                        resultView.setImageURI(Uri.parse(outputFile.toURI().toString()));
                    }

                    @Override
                    public void onCropFailed() {
                        Log.e("Cropper", "Cropping failed");
                    }
                })
                .withErrorListener(new Cropper.OnErrorListener() {
                    @Override
                    public void onError(Throwable e) {
                        Log.e("Cropper", "There was an error cropping", e);
                    }

                    @Override
                    public void onFatalError(Throwable e) {
                        Log.e("Cropper", "There was a fatal error cropping", e);
                    }
                })
                .build();
            cropper.start();
        }
    }

    @Override
    public void onStop() {
        super.onStop();

        if(cropper != null) {
            cropper.release();
            cropper = null;
        }
    }
}
