package app.siphondsp.view

/**
 * Marker documenting that sidebar tile buttons must retain MaterialButton's managed background.
 * Custom background replacement caused an on-device regression; selected state should use
 * supported stroke/tint properties while preserving the supplied tile artwork.
 */
internal object SidebarButtonRenderingNote
