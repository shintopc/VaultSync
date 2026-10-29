package com.vaultsync.feature.stealth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultsync.core.stealth.TwoFingerGestureType
import com.vaultsync.core.stealth.detectTwoFingerVaultGesture
import java.text.DecimalFormat

@Composable
fun DisguisedCalculatorScreen(
    isTwoFingerGestureEnabled: Boolean = true,
    gestureType: TwoFingerGestureType = TwoFingerGestureType.SWIPE_DOWN,
    isPinTriggerEnabled: Boolean = true,
    onAttemptPinUnlock: (String) -> Unit = {},
    onSecretAccessTriggered: () -> Unit = {},
) {
    var displayValue by remember { mutableStateOf("0") }
    var expressionHistory by remember { mutableStateOf("") }
    var firstOperand by remember { mutableStateOf<Double?>(null) }
    var pendingOperator by remember { mutableStateOf<String?>(null) }
    var shouldResetInputOnNextDigit by remember { mutableStateOf(false) }

    fun onDigit(digit: String) {
        if (shouldResetInputOnNextDigit || displayValue == "0") {
            displayValue = digit
            shouldResetInputOnNextDigit = false
        } else if (displayValue.length < 12) {
            displayValue += digit
        }
    }

    fun onDecimal() {
        if (shouldResetInputOnNextDigit) {
            displayValue = "0."
            shouldResetInputOnNextDigit = false
        } else if (!displayValue.contains(".")) {
            displayValue += "."
        }
    }

    fun onClear() {
        displayValue = "0"
        expressionHistory = ""
        firstOperand = null
        pendingOperator = null
        shouldResetInputOnNextDigit = false
    }

    fun onToggleSign() {
        val num = displayValue.toDoubleOrNull() ?: return
        displayValue = formatNumber(-num)
    }

    fun onPercentage() {
        val num = displayValue.toDoubleOrNull() ?: return
        displayValue = formatNumber(num / 100.0)
    }

    fun onOperator(op: String) {
        val currentNum = displayValue.toDoubleOrNull() ?: 0.0
        if (firstOperand == null) {
            firstOperand = currentNum
            expressionHistory = "${formatNumber(currentNum)} $op"
        } else if (pendingOperator != null && !shouldResetInputOnNextDigit) {
            val result = calculate(firstOperand!!, currentNum, pendingOperator!!)
            firstOperand = result
            displayValue = formatNumber(result)
            expressionHistory = "${formatNumber(result)} $op"
        } else {
            expressionHistory = "${formatNumber(firstOperand!!)} $op"
        }
        pendingOperator = op
        shouldResetInputOnNextDigit = true
    }

    fun onEquals() {
        // Perform standard calculator evaluation only
        if (firstOperand != null && pendingOperator != null) {
            val secondNum = displayValue.toDoubleOrNull() ?: 0.0
            val result = calculate(firstOperand!!, secondNum, pendingOperator!!)
            expressionHistory = "${formatNumber(firstOperand!!)} $pendingOperator ${formatNumber(secondNum)} ="
            displayValue = formatNumber(result)
            firstOperand = null
            pendingOperator = null
            shouldResetInputOnNextDigit = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .detectTwoFingerVaultGesture(
                enabled = isTwoFingerGestureEnabled,
                gestureType = gestureType,
                onGestureTriggered = onSecretAccessTriggered,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // Calculator Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Calculator",
                    color = Color(0xFF3A3A3C),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "DEG",
                    color = Color(0xFF2C2C2E),
                    fontSize = 12.sp,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Expression History Display
            Text(
                text = expressionHistory,
                color = Color(0xFF8E8E93),
                fontSize = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                textAlign = TextAlign.End,
                maxLines = 1,
            )

            // Primary Numbers Display
            Text(
                text = displayValue,
                color = Color.White,
                fontSize = if (displayValue.length > 8) 46.sp else 64.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                textAlign = TextAlign.End,
                maxLines = 1,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Keypad Grid
            val btnSpacing = 12.dp

            // Row 1: AC, +/-, %, ÷
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(btnSpacing),
            ) {
                CalcButton("AC", Color(0xFF505054), Color.White, Modifier.weight(1f)) { onClear() }
                CalcButton("±", Color(0xFF505054), Color.White, Modifier.weight(1f)) { onToggleSign() }
                CalcButton("%", Color(0xFF505054), Color.White, Modifier.weight(1f)) { onPercentage() }
                CalcButton("÷", Color(0xFFFF9F0A), Color.White, Modifier.weight(1f)) { onOperator("÷") }
            }

            Spacer(modifier = Modifier.height(btnSpacing))

            // Row 2: 7, 8, 9, ×
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(btnSpacing),
            ) {
                CalcButton("7", Color(0xFF2C2C2E), Color.White, Modifier.weight(1f)) { onDigit("7") }
                CalcButton("8", Color(0xFF2C2C2E), Color.White, Modifier.weight(1f)) { onDigit("8") }
                CalcButton("9", Color(0xFF2C2C2E), Color.White, Modifier.weight(1f)) { onDigit("9") }
                CalcButton("×", Color(0xFFFF9F0A), Color.White, Modifier.weight(1f)) { onOperator("×") }
            }

            Spacer(modifier = Modifier.height(btnSpacing))

            // Row 3: 4, 5, 6, -
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(btnSpacing),
            ) {
                CalcButton("4", Color(0xFF2C2C2E), Color.White, Modifier.weight(1f)) { onDigit("4") }
                CalcButton("5", Color(0xFF2C2C2E), Color.White, Modifier.weight(1f)) { onDigit("5") }
                CalcButton("6", Color(0xFF2C2C2E), Color.White, Modifier.weight(1f)) { onDigit("6") }
                CalcButton("-", Color(0xFFFF9F0A), Color.White, Modifier.weight(1f)) { onOperator("-") }
            }

            Spacer(modifier = Modifier.height(btnSpacing))

            // Row 4: 1, 2, 3, +
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(btnSpacing),
            ) {
                CalcButton("1", Color(0xFF2C2C2E), Color.White, Modifier.weight(1f)) { onDigit("1") }
                CalcButton("2", Color(0xFF2C2C2E), Color.White, Modifier.weight(1f)) { onDigit("2") }
                CalcButton("3", Color(0xFF2C2C2E), Color.White, Modifier.weight(1f)) { onDigit("3") }
                CalcButton("+", Color(0xFFFF9F0A), Color.White, Modifier.weight(1f)) { onOperator("+") }
            }

            Spacer(modifier = Modifier.height(btnSpacing))

            // Row 5: 0 (double width), ., =
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(btnSpacing),
            ) {
                CalcButton("0", Color(0xFF2C2C2E), Color.White, Modifier.weight(2f), isWide = true) { onDigit("0") }
                CalcButton(".", Color(0xFF2C2C2E), Color.White, Modifier.weight(1f)) { onDecimal() }
                CalcButton("=", Color(0xFFFF9F0A), Color.White, Modifier.weight(1f)) { onEquals() }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CalcButton(
    text: String,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isWide: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .then(if (isWide) Modifier.height(76.dp) else Modifier.aspectRatio(1f))
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = if (isWide) Alignment.CenterStart else Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            modifier = if (isWide) Modifier.padding(start = 28.dp) else Modifier,
        )
    }
}

private fun calculate(a: Double, b: Double, op: String): Double {
    return when (op) {
        "+" -> a + b
        "-" -> a - b
        "×" -> a * b
        "÷" -> if (b != 0.0) a / b else 0.0
        else -> b
    }
}

private fun formatNumber(value: Double): String {
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        val df = DecimalFormat("#.########")
        df.format(value)
    }
}
