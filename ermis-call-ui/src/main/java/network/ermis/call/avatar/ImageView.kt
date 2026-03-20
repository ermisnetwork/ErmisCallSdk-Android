package network.ermis.call.avatar

import android.widget.ImageView
import coil.load
import coil.transform.CircleCropTransformation
import network.ermis.call.core.UserCall


fun ImageView.setUserAvatar(user: UserCall, textSize: Int) {
    val placeholderDrawable = AvatarPlaceholderDrawable(context = context, name = user.name, textSizePlaceholder = textSize)
    this.load(user.avatar) {
        crossfade(true)
        placeholder(placeholderDrawable)
        error(placeholderDrawable)
        fallback(placeholderDrawable)
        transformations(CircleCropTransformation())
    }
}