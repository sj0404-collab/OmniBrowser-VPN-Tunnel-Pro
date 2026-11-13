package com.santiago43rus.rupoop

import androidx.core.content.FileProvider

/**
 * v4.87.0: свой FileProvider с отдельным authority.
 *
 * В манифесте нашего приложения уже есть FileProvider (`\${applicationId}.fileprovider`);
 * два провайдера одного класса с разными authority не сливаются, поэтому у Rupoop — свой подкласс.
 */
class RupoopFileProvider : FileProvider()
