package com.scorchedphoto.app.setup

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MatchConfigRepository @Inject constructor() {
    var matchConfig: MatchConfig? = null
}
