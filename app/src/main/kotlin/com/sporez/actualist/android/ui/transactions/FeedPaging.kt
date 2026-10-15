package com.sporez.actualist.android.ui.transactions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.core.transactions.FeedPaging

/** The feed's page size; device tests lower it to exercise "Load older transactions" on the small demo budget. */
object FeedPagingConfig {
    @Volatile
    var pageSize: Int = FeedPaging.DEFAULT_PAGE_SIZE
}

/** The row at the end of a feed that has older transactions beyond the loaded ones. */
@Composable
fun LoadOlderRow(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        TextButton(onClick = onClick, modifier = Modifier.testTag("load-older")) { Text("Load older transactions") }
    }
}
