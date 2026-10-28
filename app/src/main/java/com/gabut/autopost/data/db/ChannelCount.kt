package com.gabut.autopost.data.db

/** Row shape returned by [ChannelDao.observeChannelCounts]. */
data class ChannelCount(val tokenId: Long, val count: Int)