-- =====================================================================
-- XMR-Forecast :: V4 - catalogo de modelos (datos de referencia)
--
-- R-06 obliga a comparar contra tres baselines ademas del modelo recurrente.
-- Son definiciones logicas, no resultados: no contienen ninguna metrica, ninguna
-- cifra de rendimiento ni ninguna prediccion. Sembrarlas permite que
-- `GET /api/v1/models` responda desde el primer arranque en lugar de 404, y que
-- el selector de modelos de la interfaz tenga contenido.
--
-- NO se siembran aqui predicciones, experimentos ni metricas: R-21 prohibe
-- publicar cifras que no provengan de una corrida registrada.
-- =====================================================================

INSERT INTO models (model_key, family, task_type, description, created_at) VALUES
    ('lstm_base',     'LSTM',              'REGRESSION',
     'LSTM de una capa oculta como modelo recurrente principal.', NOW()),
    ('gru_base',      'GRU',               'REGRESSION',
     'GRU como alternativa recurrente: menos parametros que el LSTM.', NOW()),
    ('moving_average','MOVING_AVERAGE',    'REGRESSION',
     'Baseline obligatorio: media movil del cierre (R-06).', NOW()),
    ('linear_regression', 'LINEAR_REGRESSION', 'REGRESSION',
     'Baseline obligatorio: regresion lineal sobre la ventana (R-06).', NOW()),
    ('arima',         'ARIMA',             'REGRESSION',
     'Baseline obligatorio: ARIMA con pronostico rodante de un paso (R-06, R-24).', NOW())
ON CONFLICT (model_key) DO NOTHING;
