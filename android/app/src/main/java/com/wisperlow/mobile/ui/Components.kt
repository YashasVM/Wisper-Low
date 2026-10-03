package com.wisperlow.mobile.ui

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable

/** Like stringResource, but keeps <b> markup from strings.xml as bold text. */
@Composable
fun styledStringResource(@StringRes id: Int): AnnotatedString {
    val text = LocalContext.current.resources.getText(id)
    if (text !is Spanned) return AnnotatedString(text.toString())
    return buildAnnotatedString {
        append(text.toString())
        text.getSpans(0, text.length, StyleSpan::class.java).forEach { span ->
            if (span.style and Typeface.BOLD != 0) {
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), text.getSpanStart(span), text.getSpanEnd(span))
            }
        }
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
        tonalElevation = if (color == MaterialTheme.colorScheme.surface) 1.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.animateContentSize(Motion.tween()).padding(Space.Ml),
            verticalArrangement = Arrangement.spacedBy(Space.Sm),
            content = content,
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = Space.Xs, top = Space.S),
    )
}

/** Icon in a soft circle; used for feature lists and tips. */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = modifier
            .size(40.dp)
            .background(tint.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun IconTextRow(icon: ImageVector, text: AnnotatedString, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
fun IconTextRow(icon: ImageVector, text: String, modifier: Modifier = Modifier) =
    IconTextRow(icon, AnnotatedString(text), modifier)

/** Numbered instruction, e.g. "1  Tap Open settings". */
@Composable
fun NumberedStep(number: Int, text: AnnotatedString) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.Sm), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun SwitchRow(
    title: String,
    body: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(Space.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // The row handles the toggle so the whole line is one large touch target.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.semantics { role = Role.Switch })
    }
}

/** Primary call to action: one per screen, same size everywhere. */
@Composable
fun PrimaryCta(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.heightIn(min = 52.dp).androidx_widthIn(),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

private fun Modifier.androidx_widthIn(): Modifier = this.then(Modifier.widthIn(min = 120.dp))

/** Secondary action that sits beside or under a primary one. */
@Composable
fun SecondaryCta(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Large soft icon medallion that introduces an onboarding step or empty state. */
@Composable
fun HeroIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(72.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp))
    }
}
