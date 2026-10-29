package com.vaultsync.feature.stealth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultsync.core.stealth.TwoFingerGestureType
import com.vaultsync.core.stealth.detectTwoFingerVaultGesture

data class DummyNote(
    val title: String,
    val preview: String,
    val date: String,
)

@Composable
fun DisguisedNotesScreen(
    isTwoFingerGestureEnabled: Boolean = true,
    gestureType: TwoFingerGestureType = TwoFingerGestureType.SWIPE_DOWN,
    onSecretAccessTriggered: () -> Unit = {},
) {
    var activeNote by remember { mutableStateOf<DummyNote?>(null) }
    var isEditMode by remember { mutableStateOf(false) }

    val dummyNotes = remember {
        listOf(
            DummyNote(
                title = "Weekend Grocery List",
                preview = "Almond milk, olive oil, ground coffee, avocados, whole wheat bread",
                date = "Yesterday",
            ),
            DummyNote(
                title = "Meeting Notes - Sprint Planning",
                preview = "Review backlog items, update release branch milestones, schedule QA review",
                date = "Oct 21",
            ),
            DummyNote(
                title = "Home Maintenance",
                preview = "Check air filters, clean garage shelving, schedule HVAC inspection",
                date = "Oct 18",
            ),
            DummyNote(
                title = "Book Recommendations",
                preview = "Designing Data-Intensive Applications, Clean Code, Deep Work",
                date = "Oct 12",
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121214))
            .detectTwoFingerVaultGesture(
                enabled = isTwoFingerGestureEnabled,
                gestureType = gestureType,
                onGestureTriggered = onSecretAccessTriggered,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Notes",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${dummyNotes.size} Notes",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                    )
                }

                // Realistic Notes Edit Button - does NOT open gallery, only toggles note selection mode
                Box(
                    modifier = Modifier
                        .background(Color(0xFF2C2C2E), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .clickable { isEditMode = !isEditMode },
                ) {
                    Text(
                        text = if (isEditMode) "Done" else "📝 Edit",
                        color = Color(0xFFFFD60A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Search Bar Mock
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "🔍 Search notes...",
                    color = Color(0xFF636366),
                    fontSize = 15.sp,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Notes List - Clicking a note opens a realistic note reader dialog
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(dummyNotes) { note ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { activeNote = note },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = note.title,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = note.preview,
                                color = Color(0xFF8E8E93),
                                fontSize = 13.sp,
                                maxLines = 2,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = note.date,
                                color = Color(0xFF636366),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }

        // Realistic Note Details Dialog
        activeNote?.let { note ->
            AlertDialog(
                onDismissRequest = { activeNote = null },
                title = { Text(note.title, color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(note.preview, color = Color(0xFFD1D1D6), fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Created: ${note.date}", color = Color(0xFF8E8E93), fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeNote = null }) {
                        Text("Done", color = Color(0xFFFFD60A))
                    }
                },
                containerColor = Color(0xFF1C1C1E),
            )
        }
    }
}
