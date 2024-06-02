package test.videocall

import android.content.pm.PackageManager
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat


@Composable
fun permissionGrantState(permissions: Array<String>): Pair<State<PermissionsDetails>, PermissionRequester> {
    val localContext = LocalContext.current
    var launcher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>? = null

    val permissionsDetails = remember {
        val result = permissions.associateWith { permissionString ->
            ActivityCompat.checkSelfPermission(
                localContext,
                permissionString
            ) == PackageManager.PERMISSION_GRANTED
        }

        mutableStateOf(
            PermissionsDetails(
                isAllGranted = result.all { it.value },
                grantedPermissions = result.filter { it.value }.map { it.key }.toTypedArray(),
                deniedPermissions = result.filter { !it.value }.map { it.key }.toTypedArray()
            )
        )
    }

    launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permissionsDetails.value = PermissionsDetails(
                isAllGranted = result.all { it.value },
                grantedPermissions = result.filter { it.value }.map { it.key }.toTypedArray(),
                deniedPermissions = result.filter { !it.value }.map { it.key }.toTypedArray()
            )
        }

    val permissionRequester = remember {
        PermissionRequester {
            launcher.launch(permissionsDetails.value.deniedPermissions)
        }
    }

    return permissionsDetails to permissionRequester
}

class PermissionsDetails(
    val isAllGranted: Boolean = false,
    val grantedPermissions: Array<String> = emptyArray(),
    val deniedPermissions: Array<String> = emptyArray()
)

fun interface PermissionRequester {
    fun requestDeniedPermissions()
}
