package com.sticktv.player

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

object LogoLoader {
    fun load(view: ImageView, url: String?) {
        if (url.isNullOrBlank()) {
            Glide.with(view).clear(view)
            view.setImageResource(R.drawable.ic_tv)
            return
        }
        Glide.with(view)
            .load(url)
            .placeholder(R.drawable.ic_tv)
            .error(R.drawable.ic_tv)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .override(160, 100)
            .dontAnimate()
            .into(view)
    }
}
