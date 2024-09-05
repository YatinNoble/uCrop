package com.passport.photo.ucrop.util

object BlackLineManager {
    const val selectedCountryHeight01 = "selectedCountryHeight01"
    const val selectedCountryHeight02 = "selectedCountryHeight02"
    const val selectedImageHeight = "imageHeight"
    private var blackLineHeight01 = 0f
    private var blackLineHeight02 = 0f
    private var imageHeight = 1f

    fun setSelectedCountryBlackLinePoint(imageHeight: Float, height01: Float, height02: Float) {
        BlackLineManager.imageHeight = imageHeight
        blackLineHeight01 = height01
        blackLineHeight02 = height02
    }

    fun getSelectedCountryBlackLinePoint(): HashMap<String, Float> {
        val map = hashMapOf(
            selectedImageHeight to imageHeight,
            selectedCountryHeight01 to blackLineHeight01,
            selectedCountryHeight02 to blackLineHeight02
        )
        return map
    }
}