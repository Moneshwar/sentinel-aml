-- Synthetic exchange rates used to normalise monetary amounts to the base currency (INR).
-- Override/extend at runtime through the exchange_rates table.
INSERT INTO exchange_rates (from_currency, to_currency, rate, effective_from) VALUES
    ('USD', 'INR', 83.25000000, TIMESTAMPTZ '2024-01-01 00:00:00+00'),
    ('EUR', 'INR', 90.10000000, TIMESTAMPTZ '2024-01-01 00:00:00+00'),
    ('GBP', 'INR', 105.40000000, TIMESTAMPTZ '2024-01-01 00:00:00+00'),
    ('AED', 'INR', 22.67000000, TIMESTAMPTZ '2024-01-01 00:00:00+00'),
    ('SGD', 'INR', 61.80000000, TIMESTAMPTZ '2024-01-01 00:00:00+00'),
    ('CHF', 'INR', 93.50000000, TIMESTAMPTZ '2024-01-01 00:00:00+00'),
    ('HKD', 'INR', 10.65000000, TIMESTAMPTZ '2024-01-01 00:00:00+00')
ON CONFLICT (from_currency, to_currency, effective_from) DO NOTHING;
