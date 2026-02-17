package com.kyroos.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import com.kyroos.app.data.*

@Composable
fun SetupScreen(onPair: (Int, String) -> Unit) {
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(KyBg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(40.dp))
        Icon(Icons.Rounded.Forest, null, tint = KyPrimary, modifier = Modifier.size(80.dp))
        Text("KyrooS Setup", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Aktifkan Wireless Debugging", color = KyOutline)
        
        Spacer(modifier = Modifier.height(40.dp))
        
        Card(colors = CardDefaults.cardColors(containerColor = KySurface), shape = RoundedCornerShape(28.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                OutlinedTextField(
                    value = port, onValueChange = { port = it },
                    label = { Text("Port Pairing") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KyPrimary, unfocusedBorderColor = KyOutline)
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text("Kode Pairing") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KyPrimary, unfocusedBorderColor = KyOutline)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = { onPair(port.toIntOrNull() ?: 0, code) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = KyPrimary),
            shape = RoundedCornerShape(16.dp),
            enabled = port.isNotEmpty() && code.isNotEmpty()
        ) {
            Text("Hubungkan", color = KyBg, fontWeight = FontWeight.Bold)
        }
    }
}
