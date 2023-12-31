package com.baxramov.minegoinstallerforclient

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.startActivity
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var extractButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var textView: TextView


    private val pickDirectoryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.also { uri ->
                    GlobalScope.launch(Dispatchers.IO) {
                        runOnUiThread {
                            toggleViewsVisibility(View.INVISIBLE, View.VISIBLE)
                        }

                        extractZip(uri)
                        runOnUiThread {
                            toggleViewsVisibility(View.VISIBLE, View.INVISIBLE)
                        }
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        extractButton = findViewById(R.id.extractButton)
        progressBar = findViewById(R.id.progressBar)
        textView = findViewById(R.id.textView)

        extractButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestStoragePermission()
            } else {
                openDocumentTree()
            }
        }
    }

    private fun openDocumentTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata")
            )
        }
        pickDirectoryLauncher.launch(intent)
    }

    //Call anywhere in your main code
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestAllFilesPermission()
            requestDocumentPermission("data")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestAllFilesPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.addCategory("android.intent.category.DEFAULT")
        intent.data = Uri.fromParts("package", packageName, null)
        startActivity(intent)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestDocumentPermission(folder: String) {
        val storageManager = application.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val intent = storageManager.primaryStorageVolume.createOpenDocumentTreeIntent()
        val targetDirectory = "Android%2F$folder" // add your directory to be selected by the user
        var uri = intent.getParcelableExtra<Uri>("android.provider.extra.INITIAL_URI") as Uri
        var scheme = uri.toString()
        scheme = scheme.replace("/root/", "/document/")
        scheme += "%3A$targetDirectory"
        uri = Uri.parse(scheme)
        intent.putExtra("android.provider.extra.INITIAL_URI", uri)
        startActivityForResult(intent, REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == REQUEST_CODE) {
            if (data != null) {
                data.data?.let { treeUri ->

                    // treeUri is the Uri of the file

                    // if lifelong access is required, use takePersistableUriPermission()
                    contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    extractZip(treeUri)
                }
            }
        }
    }

    private fun extractZip(destinationUri: Uri) {
        val zipFileName = "com.sandboxol.blockymods.zip"

        try {
            val inputStream: InputStream = assets.open(zipFileName)
            val zipInputStream = ZipInputStream(inputStream)

            val destinationDirectory = DocumentFile.fromTreeUri(this, destinationUri)

            if (destinationDirectory != null && destinationDirectory.isDirectory) {
                try {
                    val totalBytes = inputStream.available().toFloat()
                    var bytesRead = 0
                    var zipEntry = zipInputStream.nextEntry

                    runOnUiThread {
                        toggleViewsVisibility(View.INVISIBLE, View.VISIBLE)

                    }
                    while (zipEntry != null) {
                        val entryFileName = zipEntry.name
                        val entryFilePath = entryFileName.split('/')
                        var currentDir: DocumentFile? = destinationDirectory

                        // Traverse the entryFilePath and create subdirectories
                        for (i in 0 until entryFilePath.size - 1) {
                            currentDir = currentDir?.findFile(entryFilePath[i])
                                ?: currentDir?.createDirectory(entryFilePath[i])
                        }

                        // Create the file in the last subdirectory
                        val entryFile =
                            currentDir?.createFile("application/octet-stream", entryFilePath.last())

                        entryFile?.let { it ->
                            val outputStream = contentResolver.openOutputStream(it.uri)
                            val buffer = ByteArray(4096)
                            var length: Int
                            while (zipInputStream.read(buffer).also { length = it } > 0) {
                                outputStream?.write(buffer, 0, length)
                                bytesRead += length
                                updateProgressBar(bytesRead, totalBytes)
                            }
                            outputStream?.close()
                        }

                        zipInputStream.closeEntry()
                        zipEntry = zipInputStream.nextEntry
                    }

                    zipInputStream.close()

                    runOnUiThread {
                        showToast("Extraction complete. Check the destination directory.")
                        toggleViewsVisibility(View.VISIBLE, View.INVISIBLE)
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    Log.e("MainActivity", "Error during zip extraction: ${e.message}")
                    handleExtractionFailure()
                }
            } else {
                Log.e("MainActivity", "Invalid destination directory")
                handleExtractionFailure()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e("MainActivity", "Error opening zip file: ${e.message}")
            handleExtractionFailure()
        }
    }

    private fun updateProgressBar(bytesRead: Int, totalBytes: Float) {
        val progress = (bytesRead / totalBytes * 100).toInt()
        runOnUiThread {
            progressBar.progress = progress
        }
    }

    private fun handleExtractionFailure() {
        runOnUiThread {
            showToast("Extraction failed. Please choose a valid directory.")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
    }

    private fun toggleViewsVisibility(
        buttonVisibility: Int,
        progressBarAndTextViewVisibility: Int
    ) {
        extractButton.visibility = buttonVisibility
        textView.visibility = progressBarAndTextViewVisibility
        progressBar.visibility = progressBarAndTextViewVisibility
    }

    companion object {
        private const val REQUEST_CODE = 3106
    }
}