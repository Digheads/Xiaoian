package com.xiaoian.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/**
 * Asked once, before a desktop's first start: the root password for its
 * chroot. The app does not keep it -- the script sets it with chpasswd and
 * the file it came in is deleted -- so changing it later is `passwd` inside
 * the desktop. Each desktop has its own chroot, so its own password.
 */
@Composable
fun RootPasswordDialog(deName: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = confirm.isNotEmpty() && confirm != password
    val ok = password.isNotEmpty() && password == confirm

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Password for $deName") },
        text = {
            Column {
                Text(
                    "This is the first start of $deName. Choose a password for its root user. " +
                        "Anything in the desktop that asks for authentication uses it.\n\n" +
                        "The app does not store it. To change it later, run passwd in a $deName terminal.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    isError = mismatch,
                    supportingText = { if (mismatch) Text("The passwords do not match.") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = ok) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
