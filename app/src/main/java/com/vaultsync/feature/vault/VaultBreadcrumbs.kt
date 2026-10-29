package com.vaultsync.feature.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultFolder

@Composable
fun VaultBreadcrumbs(
    breadcrumbs: List<VaultFolder>,
    selectedCategory: VaultCategory = VaultCategory.All,
    onNavigateToRoot: () -> Unit,
    onNavigateToFolder: (String) -> Unit,
    onResetCategory: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(breadcrumbs.size, selectedCategory) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val isRootActive = breadcrumbs.isEmpty() && selectedCategory == VaultCategory.All

        // Root segment
        Text(
            text = "Vault",
            fontSize = 15.sp,
            fontWeight = if (isRootActive) FontWeight.Bold else FontWeight.Medium,
            color = if (isRootActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { onNavigateToRoot() },
        )

        breadcrumbs.forEachIndexed { index, folder ->
            Text(
                text = " › ",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            val isCurrent = index == breadcrumbs.lastIndex && selectedCategory == VaultCategory.All
            Text(
                text = folder.name,
                fontSize = 15.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onNavigateToFolder(folder.id) },
            )
        }

        if (selectedCategory != VaultCategory.All) {
            Text(
                text = " › ",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            Text(
                text = "${selectedCategory.label} ✕",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onResetCategory?.invoke() },
            )
        }
    }
}

