package com.golfanalyser.app.data

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

interface GolfAnalyserApi {
    @Multipart
    @POST("analyses")
    suspend fun createAnalysis(
        @Part video: MultipartBody.Part,
        @Part("handedness") handedness: RequestBody,
        @Part("camera_view") cameraView: RequestBody,
        @Part("club_family") clubFamily: RequestBody,
        @Part("swing_type") swingType: RequestBody,
    ): AnalysisCreateResponse

    @GET("analyses")
    suspend fun listAnalyses(): List<AnalysisResultResponse>

    @GET("analyses/{runId}")
    suspend fun getAnalysis(@Path("runId") runId: String): AnalysisResultResponse

    @GET("analyses/{runId}/status")
    suspend fun getStatus(@Path("runId") runId: String): AnalysisStatusResponse

    @POST("analyses/{runId}/phases/confirm")
    suspend fun confirmPhases(
        @Path("runId") runId: String,
        @Body request: PhaseConfirmationRequest,
    ): AnalysisResultResponse

    @POST("analyses/{runId}/llm-assessment")
    suspend fun createLlmAssessment(@Path("runId") runId: String): AnalysisResultResponse
}
