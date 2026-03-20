package network.ermis.call.permission

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.permissionx.guolindev.PermissionX
import network.ermis.call.R

private const val SNACKBAR_ELEVATION_IN_DP = 20

public class PermissionChecker {

    public fun Context.openSystemSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            val uri: Uri = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = uri
        }
        startActivity(intent)
    }

    private val View.activity: FragmentActivity?
        get() {
            var context = context
            while (context is ContextWrapper) {
                if (context is FragmentActivity) {
                    return context
                }
                context = context.baseContext
            }
            return null
        }

    internal fun displayMetrics(): DisplayMetrics = Resources.getSystem().displayMetrics

    private fun Int.dpToPxPrecise(): Float = (this * displayMetrics().density)

    private fun isAllPermissionsGranted(context: Context, permissions: List<String>): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    public fun checkCameraPermissions(
        view: View,
        onPermissionDenied: () -> Unit = { },
        onPermissionGranted: () -> Unit,
    ) {
        checkPermissions(
            view,
            view.context.getString(R.string.permission_camera_title),
            view.context.getString(R.string.permission_camera_message),
            view.context.getString(R.string.permission_camera_message),
            listOf(Manifest.permission.CAMERA),
            onPermissionDenied,
            onPermissionGranted,
        )
    }

    public fun checkAudioRecordPermissions(
        view: View,
        onPermissionDenied: () -> Unit = { },
        onPermissionGranted: () -> Unit = { },
    ) {
        checkPermissions(
            view,
            view.context.getString(R.string.permission_audio_record_title),
            view.context.getString(R.string.permission_audio_record_message),
            view.context.getString(R.string.permission_audio_record_message),
            listOf(Manifest.permission.RECORD_AUDIO),
            onPermissionDenied,
            onPermissionGranted,
        )
    }

    public fun checkVideoCallPermissions(
        view: View,
        onPermissionDenied: () -> Unit = { },
        onPermissionGranted: () -> Unit = { },
    ) {
        checkPermissions(
            view,
            view.context.getString(R.string.permission_video_call_title),
            view.context.getString(R.string.permissions_rationale_msg_camera_and_audio),
            view.context.getString(R.string.permissions_rationale_msg_camera_and_audio),
            listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
            onPermissionDenied,
            onPermissionGranted,
        )
    }

    private fun isPermissionContainedOnManifest(context: Context, permission: String): Boolean =
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.contains(permission) == true

    @Suppress("LongParameterList")
    private fun checkPermissions(
        view: View,
        dialogTitle: String,
        dialogMessage: String,
        snackbarMessage: String,
        permissions: List<String>,
        onPermissionDenied: () -> Unit,
        onPermissionGranted: () -> Unit,
    ) {
        val activity = view.activity ?: return

        PermissionX.init(activity)
            .permissions(permissions)
            .onExplainRequestReason { _, _ ->
                showPermissionRationaleDialog(view.context, dialogTitle, dialogMessage)
            }
            .onForwardToSettings { _, _ ->
                showPermissionDeniedSnackbar(view, snackbarMessage)
            }
            .request { allGranted, _, _ ->
                if (allGranted) onPermissionGranted() else onPermissionDenied()
            }
    }

    /**
     * Shows permission rationale dialog.
     *
     * @param context The context to show alert dialog.
     * @param dialogTitle The title of the dialog.
     * @param dialogMessage The message to display.
     */
    private fun showPermissionRationaleDialog(
        context: Context,
        dialogTitle: String,
        dialogMessage: String,
    ) {
        AlertDialog.Builder(context)
            .setTitle(dialogTitle)
            .setMessage(dialogMessage)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Shows a [Snackbar] whenever a permission has been denied.
     *
     * @param view The anchor view for the Snackbar.
     * @param snackbarMessage The message displayed in the Snackbar.
     */
    private fun showPermissionDeniedSnackbar(
        view: View,
        snackbarMessage: String,
    ) {
        Snackbar.make(view, snackbarMessage, Snackbar.LENGTH_LONG).apply {
            setAction(R.string.permissions_setting_button) {
                context.openSystemSettings()
            }
            addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                override fun onShown(sb: Snackbar?) {
                    super.onShown(sb)
                    sb?.view?.elevation = SNACKBAR_ELEVATION_IN_DP.dpToPxPrecise()
                }
            })
            show()
        }
    }
}
