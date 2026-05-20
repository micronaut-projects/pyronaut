from jakarta.inject import Singleton
from micronaut.http.annotation import Controller, Get, Produces

from example.micronaut.weather.model.forecast import Forecast
from example.micronaut.weather.model.forecast_properties import ForecastProperties
from example.micronaut.weather.model.period import Period


@Singleton
class ForecastService:
    def forecast(self) -> Forecast:
        return Forecast(
            ForecastProperties(
                periods=[
                    Period(
                        temperature=68,
                        temperatureUnit="F",
                        windSpeed="9 mph",
                        windDirection="NW",
                        detailedForecast="Clear near MTR grid 88,126",
                    )
                ]
            )
        )


@Controller("/forecast")
class ForecastController:
    def __init__(self, forecastService: ForecastService):
        self.forecastService = forecastService

    @Produces("application/json")
    @Get("/")
    def forecast(self) -> object:
        return self.forecastService.forecast()
