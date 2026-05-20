from typing import Annotated

from micronaut.context.annotation import Requires
from micronaut.http.annotation import Controller, Get, PathVariable, Produces

from example.micronaut.weather.model.forecast import Forecast
from example.micronaut.weather.model.forecast_properties import ForecastProperties
from example.micronaut.weather.model.period import Period


@Requires(property="spec.name", value="PythonSerdeSerializationTest")
@Controller
class TestWeatherApi:
    @Produces("application/json")
    @Get("/gridpoints/{grid_id}/{grid_x},{grid_y}/forecast")
    def forecast(
        self,
        grid_id: Annotated[str, PathVariable],
        grid_x: Annotated[str, PathVariable],
        grid_y: Annotated[str, PathVariable],
    ) -> Forecast:
        return Forecast(
            ForecastProperties(
                periods=[
                    Period(
                        temperature=68,
                        temperatureUnit="F",
                        windSpeed="9 mph",
                        windDirection="NW",
                        detailedForecast=f"Clear near {grid_id} grid {grid_x},{grid_y}",
                    )
                ]
            )
        )
