package com.istech.buscourse

import android.app.Application

/**
 * BusCourse アプリケーションクラス。
 * アプリ全体で共有するシングルトン（Room DB 等）の初期化起点。オフライン厳守（ネットワーク接続経路を持たない）。
 */
class BusCourseApplication : Application()
