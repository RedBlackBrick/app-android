package com.tradingplatform.app.data.api

import com.tradingplatform.app.data.model.LoginRequestDto
import com.tradingplatform.app.data.model.LoginResponseDto
import com.tradingplatform.app.data.model.PortfolioListResponseDto
import com.tradingplatform.app.data.model.TokenResponseDto
import com.tradingplatform.app.data.model.TotpVerifyRequestDto
import com.tradingplatform.app.data.model.TotpVerifyResponseDto
import com.tradingplatform.app.data.model.UserDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthApi {
    @POST("v1/auth/login")
    suspend fun login(@Body request: LoginRequestDto): Response<LoginResponseDto>

    @POST("v1/auth/refresh")
    suspend fun refresh(): Response<TokenResponseDto>

    @POST("v1/auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("v1/auth/me")
    suspend fun me(): Response<UserDto>

    @POST("v1/auth/2fa/verify")
    suspend fun verify2fa(@Body request: TotpVerifyRequestDto): Response<TotpVerifyResponseDto>

    @GET("v1/portfolios")
    suspend fun getPortfolios(): Response<PortfolioListResponseDto>
}
